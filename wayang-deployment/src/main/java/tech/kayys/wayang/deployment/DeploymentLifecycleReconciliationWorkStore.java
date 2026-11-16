package tech.kayys.wayang.deployment;

import java.time.Instant;

/** Cross-process lease and outcome store for lifecycle reconciliation workers. */
public interface DeploymentLifecycleReconciliationWorkStore {
    /** Atomically acquires work that is new, due for retry, or has an expired worker lease. */
    boolean claim(DeploymentOperationReceipt receipt, Instant now, Instant leaseUntil);

    /** Completes a claim or schedules a bounded retry. Must update only a currently CLAIMED row. */
    void complete(DeploymentOperationReceipt receipt, DeploymentLifecycleReconciliationWorkStatus status,
            String detail, Instant now, Instant retryAt);
}
