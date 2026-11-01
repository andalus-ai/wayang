package tech.kayys.wayang.runtime;

/**
 * Told whenever a node's configuration asks for a {@code ${secret:NAME}}: which tenant, process, run and node, and which name,
 * and whether it exists. Never the value. An exception thrown by a listener is swallowed: auditing must not fail a run.
 */
@FunctionalInterface
public interface SecretAccessListener {

    void accessed(String tenantId, String processId, String runId, String nodeId, String secretName, boolean found);
}
