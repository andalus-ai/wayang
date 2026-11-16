package tech.kayys.wayang.rest.management;

import java.io.IOException;
import java.net.URI;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.logging.Logger;

import jakarta.annotation.PreDestroy;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.scheduler.Scheduled;
import tech.kayys.wayang.management.client.ManagementApiException;
import tech.kayys.wayang.management.client.ManagementClient;
import tech.kayys.wayang.management.client.ManagementContracts.AckRequest;
import tech.kayys.wayang.management.client.ManagementContracts.AckResponse;
import tech.kayys.wayang.management.client.ManagementContracts.CommandPage;
import tech.kayys.wayang.management.client.ManagementContracts.CommandView;

/**
 * Opt-in command delivery loop. It polls only when at least one explicit handler exists,
 * acknowledges STARTED before invoking a handler, and never executes an unrecognized command.
 *
 * <p>Delivery is at-least-once. Handlers MUST durably deduplicate using both commandId and
 * idempotencyKey because a process can fail after execution but before its terminal acknowledgement.</p>
 */
@ApplicationScoped
public class RuntimeManagementCommandPoller {
    private static final Logger LOG = Logger.getLogger(RuntimeManagementCommandPoller.class.getName());
    private static final int MAX_POLL_LIMIT = 50;
    private static final int MAX_BACKOFF_SECONDS = 300;
    private static final String APPLY_DEPLOYMENT = "APPLY_DEPLOYMENT";

    @ConfigProperty(name = "wayang.management.enabled", defaultValue = "false")
    boolean enabled;
    @ConfigProperty(name = "wayang.management.endpoint")
    Optional<String> endpoint;
    @ConfigProperty(name = "wayang.management.connect-timeout", defaultValue = "5S")
    Duration connectTimeout;
    @ConfigProperty(name = "wayang.management.request-timeout", defaultValue = "10S")
    Duration requestTimeout;
    @ConfigProperty(name = "wayang.management.command-poll-limit", defaultValue = "10")
    int pollLimit;

    @Inject ManagementCredentialStore credentials;
    @Inject Instance<RuntimeManagementCommandHandler> handlerInstances;

    private final AtomicBoolean closed = new AtomicBoolean();
    private volatile long nextAttemptNanos;
    private volatile int consecutiveFailures;
    private volatile String rejectedCredentialFingerprint;

    @Scheduled(every = "${wayang.management.command-poll-interval:10s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void poll() {
        if (!enabled || closed.get() || System.nanoTime() < nextAttemptNanos) return;

        List<RuntimeManagementCommandHandler> handlers = handlerInstances.stream().toList();
        if (handlers.isEmpty()) return;

        final Map<String, RuntimeManagementCommandHandler> byType;
        try {
            byType = indexHandlers(handlers);
        } catch (IllegalStateException ambiguous) {
            LOG.severe("Runtime management polling disabled for this tick: " + ambiguous.getMessage());
            return;
        }

        String tokenUsed = null;
        try {
            Optional<String> token = credentials.load();
            if (token.isEmpty()) return;
            tokenUsed = token.get();
            if (fingerprint(tokenUsed).equals(rejectedCredentialFingerprint)) return;
            ManagementClient client = newClient();
            CommandPage page = client.pollCommands(tokenUsed, boundedPollLimit());
            consecutiveFailures = 0;
            nextAttemptNanos = 0L;
            for (CommandView command : page.commands()) {
                if (closed.get()) break;
                process(client, tokenUsed, command, byType);
            }
        } catch (ManagementApiException failure) {
            if (failure.credentialRejected()) {
                credentialsRejected(tokenUsed);
                return;
            }
            scheduleBackoff();
            LOG.warning("Runtime management command poll failed with HTTP " + failure.statusCode());
        } catch (IOException | RuntimeException failure) {
            scheduleBackoff();
            LOG.warning("Runtime management command poll failed (" + failure.getClass().getSimpleName() + ")");
        }
    }

    private void process(ManagementClient client, String token, CommandView command,
            Map<String, RuntimeManagementCommandHandler> handlers) {
        if (command == null || command.commandId() == null) return;
        UUID commandId = command.commandId();
        String type = command.commandType() == null ? "" : command.commandType().trim();
        Instant expiresAt = command.expiresAt();
        if (expiresAt == null || !expiresAt.isAfter(Instant.now())) return;

        RuntimeManagementCommandHandler handler = handlers.get(type);
        if (handler == null) {
            acknowledge(client, token, commandId,
                    new AckRequest("FAILED", "Unsupported command type; no runtime handler is registered", null));
            LOG.warning("Rejected unsupported management command " + commandId + " (" + type + ")");
            return;
        }

        try {
            AckResponse started = client.acknowledge(token, commandId,
                    new AckRequest("STARTED", "Runtime accepted command for execution", null));
            if (!started.accepted()) {
                LOG.warning("Console did not accept STARTED acknowledgement for command " + commandId);
                return;
            }
        } catch (IOException | RuntimeException acknowledgementFailure) {
            LOG.warning("Unable to acknowledge command start; execution skipped for " + commandId);
            return;
        }

        RuntimeManagementCommandHandler.Command input = new RuntimeManagementCommandHandler.Command(
                commandId.toString(), command.idempotencyKey(), type, command.typedPayload());
        try {
            RuntimeManagementCommandHandler.CommandResult result = handler.execute(input);
            if (result == null || !result.successful()) {
                String message = result == null ? "Command handler returned no result"
                        : safeMessage(result.message(), "Command handler reported failure");
                acknowledge(client, token, commandId, new AckRequest("FAILED", message, null));
                return;
            }

            String digest = result.verifiedArtifactDigest();
            if (APPLY_DEPLOYMENT.equals(type) && !isSha256Digest(digest)) {
                acknowledge(client, token, commandId, new AckRequest("FAILED",
                        "Deployment handler did not return a verified sha256 artifact digest", null));
                return;
            }
            acknowledge(client, token, commandId,
                    new AckRequest("SUCCEEDED", safeMessage(result.message(), "Command completed"), digest));
        } catch (Exception executionFailure) {
            acknowledge(client, token, commandId, new AckRequest("FAILED",
                    "Command handler failed: " + executionFailure.getClass().getSimpleName(), null));
            LOG.warning("Management command handler failed for " + commandId + " (" + type + ")");
        }
    }

    private void acknowledge(ManagementClient client, String token, UUID commandId, AckRequest request) {
        try {
            AckResponse response = client.acknowledge(token, commandId, request);
            if (!response.accepted()) {
                LOG.warning("Console rejected " + request.status() + " acknowledgement for command " + commandId);
            }
        } catch (IOException | RuntimeException failure) {
            LOG.warning("Unable to send " + request.status() + " acknowledgement for command " + commandId
                    + "; a later delivery may repeat it");
        }
    }

    private ManagementClient newClient() {
        String base = endpoint.orElseThrow(() ->
                new IllegalStateException("wayang.management.endpoint is required when management is enabled"));
        if (!base.regionMatches(true, 0, "https://", 0, 8)) {
            throw new IllegalStateException("Runtime management endpoint must use HTTPS");
        }
        return new ManagementClient(URI.create(base), connectTimeout, requestTimeout);
    }

    private int boundedPollLimit() {
        return Math.max(1, Math.min(pollLimit, MAX_POLL_LIMIT));
    }

    private void credentialsRejected(String rejectedToken) {
        rejectedCredentialFingerprint = rejectedToken == null ? null : fingerprint(rejectedToken);
        LOG.severe("Runtime management credential rejected; command polling is paused until the stored token changes");
    }

    private void scheduleBackoff() {
        consecutiveFailures = Math.min(consecutiveFailures + 1, 16);
        long exponential = Math.min(MAX_BACKOFF_SECONDS, 1L << Math.min(consecutiveFailures, 8));
        double jitter = ThreadLocalRandom.current().nextDouble(0.5d, 1.5d);
        long delayMillis = Math.min(MAX_BACKOFF_SECONDS * 1000L, (long) (exponential * jitter * 1000d));
        long delayNanos = Duration.ofMillis(delayMillis).toNanos();
        nextAttemptNanos = System.nanoTime() + delayNanos;
    }

    static Map<String, RuntimeManagementCommandHandler> indexHandlers(
            List<RuntimeManagementCommandHandler> handlers) {
        Map<String, RuntimeManagementCommandHandler> result = new HashMap<>();
        for (RuntimeManagementCommandHandler handler : handlers) {
            if (handler == null || handler.commandTypes() == null || handler.commandTypes().isEmpty()) {
                throw new IllegalStateException("Every runtime command handler must declare at least one command type");
            }
            for (String declared : handler.commandTypes()) {
                if (declared == null || declared.isBlank()) {
                    throw new IllegalStateException("Runtime command handler declared a blank command type");
                }
                String type = declared.trim();
                if (result.putIfAbsent(type, handler) != null) {
                    throw new IllegalStateException("Multiple runtime handlers declare command type '" + type + "'");
                }
            }
        }
        return Map.copyOf(result);
    }

    static boolean isSha256Digest(String digest) {
        return digest != null && digest.trim().matches("(?i)sha256:[0-9a-f]{64}");
    }

    private static String safeMessage(String value, String fallback) {
        if (value == null || value.isBlank()) return fallback;
        String normalized = value.replaceAll("[\\r\\n\\t]+", " ").trim();
        return normalized.length() <= 900 ? normalized : normalized.substring(0, 900);
    }

    private static String fingerprint(String token) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(token.getBytes(java.nio.charset.StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is unavailable", impossible);
        }
    }

    @PreDestroy
    void shutdown() {
        closed.set(true);
    }
}
