package tech.kayys.wayang.spike;

import java.time.Duration;
import java.util.Objects;

/** Immutable host-side concurrency and queue limits for expression evaluation. */
public record EvaluationAdmissionPolicy(
        int maxConcurrent,
        int maxQueued,
        int maxConcurrentPerTenant,
        int maxQueuedPerTenant,
        Duration maxQueueWait) {

    public EvaluationAdmissionPolicy {
        if (maxConcurrent < 1 || maxQueued < 0
                || maxConcurrentPerTenant < 1
                || maxConcurrentPerTenant > maxConcurrent
                || maxQueuedPerTenant < 0
                || maxQueuedPerTenant > maxQueued) {
            throw new IllegalArgumentException("Invalid evaluation admission limits");
        }
        maxQueueWait = Objects.requireNonNull(maxQueueWait, "maxQueueWait");
        if (maxQueueWait.isNegative()) {
            throw new IllegalArgumentException("maxQueueWait must not be negative");
        }
    }

    public static EvaluationAdmissionPolicy conservativeDefaults() {
        return new EvaluationAdmissionPolicy(16, 64, 4, 16, Duration.ofSeconds(2));
    }
}
