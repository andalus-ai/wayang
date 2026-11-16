package tech.kayys.wayang.deployment;

/** Outcome of applying a terminal operation receipt to its pending lifecycle transition. */
public record DeploymentLifecycleReconciliationResult(
        Status status,
        DeploymentSnapshot snapshot,
        String detail) {
    public enum Status {
        RECONCILED,
        ALREADY_RECONCILED,
        NOT_APPLICABLE,
        WAITING_FOR_HEALTH,
        UNRESOLVED,
        CONFLICT
    }

    public DeploymentLifecycleReconciliationResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        if (detail != null && detail.length() > 2048) {
            throw new IllegalArgumentException("detail exceeds 2048 characters");
        }
    }
}
