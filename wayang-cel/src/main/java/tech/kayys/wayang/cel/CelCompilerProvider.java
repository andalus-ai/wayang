package tech.kayys.wayang.cel;

/** Vendor parser, checked AST and runtime types stay behind this provider boundary. */
public interface CelCompilerProvider {
    String providerId();
    String providerVersion();
    CelCompileResult compile(CelCompileRequest request);

    /** Capability claims must be backed by provider-specific qualification tests. */
    default CelProviderCapabilities capabilities() {
        return CelProviderCapabilities.unqualified();
    }
}
