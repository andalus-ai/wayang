package tech.kayys.wayang.spike;

import java.util.List;

/** Immutable result of one pressure observation and its hysteresis decision. */
public record EvaluationPressureTransition(
        EvaluationPressureAssessment.Level previousLevel,
        EvaluationPressureAssessment.Level currentLevel,
        EvaluationPressureAssessment.Level observedLevel,
        int consecutiveCandidateSamples,
        boolean changed,
        List<EvaluationPressureAssessment.Reason> observedReasons) {
    public EvaluationPressureTransition {
        if (previousLevel == null || currentLevel == null || observedLevel == null
                || observedReasons == null) {
            throw new NullPointerException("pressure levels and observed reasons are required");
        }
        if (consecutiveCandidateSamples < 0) {
            throw new IllegalArgumentException("consecutiveCandidateSamples must not be negative");
        }
        observedReasons = List.copyOf(observedReasons);
        if (changed != (previousLevel != currentLevel)) {
            throw new IllegalArgumentException("changed must match the level transition");
        }
    }
}
