package tech.kayys.wayang.rest.management;

import java.io.IOException;
import java.net.URI;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Level;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import io.quarkus.scheduler.Scheduled;
import tech.kayys.wayang.management.client.ManagementApiException;
import tech.kayys.wayang.management.client.ManagementClient;

/**
 * Opt-in host lifecycle integration. It only sends heartbeat and inventory while a previously
 * enrolled registration token exists; it never retries or silently repeats one-time enrollment.
 */
@ApplicationScoped
public class RuntimeManagementHeartbeat {
    private static final Logger LOG = Logger.getLogger(RuntimeManagementHeartbeat.class.getName());

    @ConfigProperty(name = "wayang.management.enabled", defaultValue = "false")
    boolean enabled;
    @ConfigProperty(name = "wayang.management.endpoint")
    Optional<String> endpoint;
    @ConfigProperty(name = "wayang.management.connect-timeout", defaultValue = "5S")
    Duration connectTimeout;
    @ConfigProperty(name = "wayang.management.request-timeout", defaultValue = "10S")
    Duration requestTimeout;

    @Inject ManagementCredentialStore credentials;
    @Inject Instance<RuntimeManagementInventoryProvider> inventoryProviders;

    @Scheduled(every = "${wayang.management.heartbeat-interval:30s}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void heartbeat() {
        if (!enabled) return;
        withClient((client, token) -> client.heartbeat(token, "HEALTHY"));
    }

    @Scheduled(every = "${wayang.management.inventory-interval:2m}", concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void reportInventory() {
        if (!enabled) return;
        withClient((client, token) -> {
            if (!inventoryProviders.isResolvable()) {
                LOG.warning("Management inventory skipped: no RuntimeManagementInventoryProvider is registered");
                return null;
            }
            return client.reportInventory(token, inventoryProviders.get().snapshot());
        });
    }

    private void withClient(ManagementOperation operation) {
        try {
            Optional<String> token = credentials.load();
            if (token.isEmpty()) {
                LOG.fine("Runtime management is enabled but no registration token is stored; enroll explicitly first");
                return;
            }
            String base = endpoint.orElseThrow(() ->
                    new IllegalStateException("wayang.management.endpoint is required when management is enabled"));
            if (!base.regionMatches(true, 0, "https://", 0, 8)) {
                throw new IllegalStateException("Runtime management endpoint must use HTTPS");
            }
            operation.apply(new ManagementClient(URI.create(base), connectTimeout, requestTimeout), token.get());
        } catch (ManagementApiException rejected) {
            if (rejected.credentialRejected()) {
                LOG.severe("Runtime management credential rejected (HTTP " + rejected.statusCode()
                        + "); operator re-enrollment is required");
            } else if (rejected.retryable()) {
                LOG.warning("Runtime management request is temporarily unavailable (HTTP "
                        + rejected.statusCode() + "); next scheduled attempt will retry");
            } else {
                LOG.warning("Runtime management request failed (HTTP " + rejected.statusCode() + ")");
            }
        } catch (IOException | RuntimeException failure) {
            LOG.log(Level.WARNING, "Runtime management operation failed; no credential values were logged", failure);
        }
    }

    @FunctionalInterface
    private interface ManagementOperation {
        Object apply(ManagementClient client, String token) throws IOException;
    }
}
