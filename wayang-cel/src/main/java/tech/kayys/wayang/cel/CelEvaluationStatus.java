package tech.kayys.wayang.cel;

/** Stable, engine-neutral evaluation outcomes. */
public enum CelEvaluationStatus {
    SUCCESS, MISSING_VARIABLE, TYPE_ERROR, EVALUATION_ERROR, BUDGET_EXCEEDED,
    CANCELLED, ARTIFACT_INCOMPATIBLE, FUNCTION_ERROR, RESULT_INVALID,
    SECURITY_REJECTED, RESOURCE_EXHAUSTED
}
