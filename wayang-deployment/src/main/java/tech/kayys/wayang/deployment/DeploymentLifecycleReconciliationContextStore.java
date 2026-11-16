package tech.kayys.wayang.deployment;

import java.util.Optional;

/** Durable, immutable storage for encrypted original deployment request context. */
public interface DeploymentLifecycleReconciliationContextStore {
    /** Stores the context once. Repeating the identical context is idempotent; a different context is rejected. */
    void save(DeploymentLifecycleReconciliationContext context);

    /** Finds context only when all receipt identity fields match. */
    Optional<DeploymentLifecycleReconciliationContext> find(DeploymentOperationReceipt receipt);
}
