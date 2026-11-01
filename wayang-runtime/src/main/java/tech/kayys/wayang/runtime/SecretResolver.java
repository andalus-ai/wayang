package tech.kayys.wayang.runtime;

/**
 * Resolves {@code ${secret:NAME}} references in node configuration at
 * execution time. Implementations must be tenant-scoped: a run of tenant A can
 * never read a secret of tenant B.
 */
@FunctionalInterface
public interface SecretResolver {

    /** Variable the engines set (overriding any caller-supplied value) to the owning tenant of a run. */
    String TENANT_VARIABLE = "wayang.tenant";

    /** Variable the engines set (overriding any caller-supplied value) to the id of the process a run executes. */
    String PROCESS_VARIABLE = "wayang.process";

    /** @return the plaintext, or null when no such secret exists for the tenant */
    String resolve(String tenantId, String name);
}
