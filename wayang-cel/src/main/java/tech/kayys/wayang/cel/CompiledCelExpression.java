package tech.kayys.wayang.cel;

/** Immutable reference to a separately stored and verified compiled artifact. */
public record CompiledCelExpression(String artifactId, String artifactFingerprint,
        String artifactFormatVersion, String compilerProviderId, String compilerProviderVersion,
        String environmentFingerprint, String scopeFingerprint,
        String functionRegistryFingerprint, String semanticProfileFingerprint) {
    public CompiledCelExpression {
        require(artifactId, "artifactId"); require(artifactFingerprint, "artifactFingerprint");
        require(artifactFormatVersion, "artifactFormatVersion"); require(compilerProviderId, "compilerProviderId");
        require(compilerProviderVersion, "compilerProviderVersion"); require(environmentFingerprint, "environmentFingerprint");
        require(scopeFingerprint, "scopeFingerprint"); require(functionRegistryFingerprint, "functionRegistryFingerprint");
        require(semanticProfileFingerprint, "semanticProfileFingerprint");
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
