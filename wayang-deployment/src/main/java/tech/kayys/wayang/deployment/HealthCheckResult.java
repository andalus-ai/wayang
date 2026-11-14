package tech.kayys.wayang.deployment;

import java.time.Instant;
import java.util.Map;

/** Bounded health observation with named checks and a normalized overall outcome. */
public record HealthCheckResult(HealthOutcome outcome, Map<String, Boolean> checks, String detail, Instant checkedAt) {
    public HealthCheckResult {
        if (outcome == null) throw new IllegalArgumentException("outcome is required");
        checks = Map.copyOf(checks == null ? Map.of() : checks);
        if (checks.size() > 128) throw new IllegalArgumentException("too many health checks");
        if (detail != null && detail.length() > 2048) throw new IllegalArgumentException("detail exceeds 2048 characters");
        if (checkedAt == null) throw new IllegalArgumentException("checkedAt is required");
    }
}
