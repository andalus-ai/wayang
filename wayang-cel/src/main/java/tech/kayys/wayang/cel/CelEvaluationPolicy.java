package tech.kayys.wayang.cel;

/** Bounded policy for preview and runtime evaluation. */
public record CelEvaluationPolicy(long maxCost, int maxCollectionElements,
        int maxResultBytes, long maxDurationMillis, String policyFingerprint) {
    public CelEvaluationPolicy {
        if (maxCost < 1 || maxCollectionElements < 1 || maxResultBytes < 1 || maxDurationMillis < 1)
            throw new IllegalArgumentException("All CEL limits must be positive");
        if (policyFingerprint == null || policyFingerprint.isBlank())
            throw new IllegalArgumentException("policyFingerprint is required");
    }
}
