package tech.kayys.wayang.deployment;

/** Result of an atomic idempotency claim. Only the caller with acquired=true may dispatch the side effect. */
public record DeploymentOperationClaim(DeploymentOperationReceipt receipt, boolean acquired) {
    public DeploymentOperationClaim {
        if (receipt == null) throw new IllegalArgumentException("receipt is required");
        if (acquired && receipt.status() != DeploymentOperationStatus.RUNNING) {
            throw new IllegalArgumentException("an acquired operation must be RUNNING");
        }
    }
}
