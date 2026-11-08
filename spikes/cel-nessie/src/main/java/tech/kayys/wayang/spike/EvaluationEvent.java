package tech.kayys.wayang.spike;

import java.util.Objects;

/** Low-cardinality, value-free observation emitted by the host evaluation boundary. */
public record EvaluationEvent(Type type, long durationNanos, String reasonCode) {
    public EvaluationEvent {
        Objects.requireNonNull(type, "type");
        if (durationNanos < 0) throw new IllegalArgumentException("durationNanos must not be negative");
        if (reasonCode != null && !reasonCode.matches("[A-Z0-9_]{1,80}")) {
            throw new IllegalArgumentException("reasonCode must be a stable uppercase code");
        }
    }

    public enum Type {
        ADMISSION_GRANTED, ADMISSION_REJECTED, EXECUTION_SUBMITTED, EXECUTION_REJECTED,
        EXECUTION_STARTED, EXECUTION_COMPLETED, EXECUTION_FAILED,
        EXECUTION_CANCELLED_BEFORE_START, EXECUTION_DEADLINE_BEFORE_START,
        CALLER_WAIT_TIMED_OUT, CALLER_INTERRUPTED, EXECUTION_RESULT_UNAVAILABLE
    }
}
