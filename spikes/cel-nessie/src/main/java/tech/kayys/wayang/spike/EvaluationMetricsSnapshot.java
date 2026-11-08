package tech.kayys.wayang.spike;

/** Immutable aggregate snapshot; deliberately contains no tenant or request dimensions. */
public record EvaluationMetricsSnapshot(long admissionGranted, long admissionRejected,
 long executionSubmitted, long executionRejected, long executionStarted,
 long executionCompleted, long executionFailed, long callerWaitTimedOut,
 long executionCancelledBeforeStart, long executionDeadlineBeforeStart,
 long executionResultUnavailable, long admissionDurationCount,
 long admissionDurationTotalNanos, long executionDurationCount,
 long executionDurationTotalNanos, long executionDurationMaxNanos) { }
