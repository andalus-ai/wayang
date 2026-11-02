package tech.kayys.wayang.api.trigger;

import java.time.Instant;
import java.util.Map;

/** A rule "when this happens, start that process". Stored per tenant; never contains plaintext secrets. */
public record Trigger(
        String tenant,
        String id,
        Kind kind,
        String processId,
        boolean enabled,
        /** SCHEDULE: {@code cron}, {@code timezone}, {@code graceSeconds}, {@code input}. POLL: {@code source} (poller name), {@code everySeconds}, source settings (may hold {@code ${secret:NAME}}). */
        Map<String, Object> config,
        /** POLL: where the last successful poll ended. SCHEDULE: unused. */
        String cursor,
        /** WEBHOOK: SHA-256 of the secret (the secret itself is shown once, at creation). */
        String secretHash,
        Instant lastPolledAt,
        String lastError,
        int consecutiveFailures,
        String createdBy,
        Instant createdAt) {

    public enum Kind { WEBHOOK, POLL, SCHEDULE }

    public Trigger {
        config = config == null ? Map.of() : Map.copyOf(config);
    }

    public Trigger with(boolean enabled, String cursor, Instant lastPolledAt, String lastError, int failures) {
        return new Trigger(tenant, id, kind, processId, enabled, config, cursor, secretHash, lastPolledAt, lastError, failures,
                createdBy, createdAt);
    }
}
