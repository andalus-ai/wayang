package tech.kayys.wayang.deployment;

/** Durable processing states for lifecycle reconciliation work. */
public enum DeploymentLifecycleReconciliationWorkStatus {
    CLAIMED,
    WAITING,
    RECONCILED,
    ALREADY_RECONCILED,
    NOT_APPLICABLE,
    UNRESOLVED,
    CONFLICT
}
