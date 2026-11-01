package tech.kayys.wayang.runtime;

/**
 * Resolves {@code ${config:NAME}} references in node configuration at execution time: plain, non-secret settings that differ
 * between environments (a base URL, a channel, a threshold). Implementations must be tenant-scoped. Unlike secrets, values are
 * not redacted from outputs.
 */
@FunctionalInterface
public interface ConfigResolver {

    /**
     * @param processId the process being run (lets an implementation prefer the value of the project that owns it); may be null
     * @return the value, or null when no such setting exists for the tenant
     */
    String resolve(String tenantId, String processId, String name);
}
