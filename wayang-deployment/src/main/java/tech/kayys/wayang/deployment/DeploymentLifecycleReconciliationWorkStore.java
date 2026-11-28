package tech.kayys.wayang.deployment;

import java.time.Instant;

/** Cross-process lease and outcome store for lifecycle reconciliation workers. */
public interface DeploymentLifecycleReconciliationWorkStore {
    /** Legacy claim API. New workers must use {@link #claimOwned}. */
    boolean claim(DeploymentOperationReceipt receipt, Instant now, Instant leaseUntil);

    /**
     * Acquires work and returns an opaque ownership token. Implementations must fail closed
     * unless they can persist and compare the token atomically across processes.
     */
    default DeploymentLifecycleReconciliationWorkClaim claimOwned(
            DeploymentOperationReceipt receipt, Instant now, Instant leaseUntil) {
        throw new UnsupportedOperationException("Ownership-fenced reconciliation claims are required");
    }

    /** Legacy completion API retained for existing integrations; workers must use fenced completion. */
    void complete(DeploymentOperationReceipt receipt, DeploymentLifecycleReconciliationWorkStatus status,
            String detail, Instant now, Instant retryAt);

    /** Completes only if the supplied token still owns the current claim. */
    default void complete(DeploymentLifecycleReconciliationWorkClaim claim,
            DeploymentLifecycleReconciliationWorkStatus status, String detail, Instant now, Instant retryAt) {
        throw new UnsupportedOperationException("Ownership-fenced reconciliation completion is required");
    }
}
