package tech.kayys.wayang.spike;

import java.util.List;

/** Immutable capacity assessment; contains only aggregate pressure dimensions. */
public record EvaluationPressureAssessment(Level level, List<Reason> reasons) {
    public EvaluationPressureAssessment {
        if (level == null || reasons == null) throw new NullPointerException("level and reasons are required");
        reasons = List.copyOf(reasons);
    }

    public enum Level { NORMAL, ELEVATED, SATURATED }

    public enum Reason {
        ADMISSION_ACTIVE_ELEVATED,
        ADMISSION_ACTIVE_SATURATED,
        ADMISSION_QUEUE_ELEVATED,
        ADMISSION_QUEUE_SATURATED,
        PROVIDER_WORKERS_ELEVATED,
        PROVIDER_WORKERS_SATURATED,
        PROVIDER_QUEUE_ELEVATED,
        PROVIDER_QUEUE_SATURATED,
        ADMISSION_CLOSED,
        EXECUTOR_SHUTDOWN
    }
}
