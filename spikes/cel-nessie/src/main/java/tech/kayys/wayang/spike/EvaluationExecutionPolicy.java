package tech.kayys.wayang.spike;

import java.time.Duration;
import java.util.Objects;

/**
 * Host policy for execution waiting. This timeout bounds how long the caller
 * waits for a result; it is not a provider CPU or execution-time limit.
 */
public record EvaluationExecutionPolicy(
        int maxWorkers,
        int maxQueuedTasks,
        Duration callerWaitTimeout) {
    public EvaluationExecutionPolicy {
        if (maxWorkers < 1 || maxQueuedTasks < 0) {
            throw new IllegalArgumentException("Invalid worker or queue limit");
        }
        callerWaitTimeout = Objects.requireNonNull(callerWaitTimeout, "callerWaitTimeout");
        if (callerWaitTimeout.isNegative() || callerWaitTimeout.isZero()) {
            throw new IllegalArgumentException("callerWaitTimeout must be positive");
        }
    }
}
