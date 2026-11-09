package tech.kayys.wayang.cel;

/** Compile source and all semantic dependencies required for reproducibility. */
public record CelCompileRequest(String expressionId, String source, String expectedResultType,
        String environmentFingerprint, String scopeFingerprint, String functionRegistryFingerprint,
        String semanticProfileFingerprint, String compilePolicyFingerprint) {
    public CelCompileRequest {
        require(expressionId, "expressionId");
        if (source == null || source.isBlank()) throw new IllegalArgumentException("source must not be blank");
        require(expectedResultType, "expectedResultType"); require(environmentFingerprint, "environmentFingerprint");
        require(scopeFingerprint, "scopeFingerprint"); require(functionRegistryFingerprint, "functionRegistryFingerprint");
        require(semanticProfileFingerprint, "semanticProfileFingerprint"); require(compilePolicyFingerprint, "compilePolicyFingerprint");
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
