package tech.kayys.wayang.deployment;

/**
 * Receipt-aware orchestration outcome. Replays expose the current lifecycle snapshot and durable
 * receipt, but deliberately contain no target result because no target call occurred in this request.
 */
public record DeploymentOrchestrationExecution<T>(
        boolean dispatched,
        T result,
        DeploymentSnapshot snapshot,
        DeploymentOperationReceipt receipt) {
    public DeploymentOrchestrationExecution {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        if (receipt == null) throw new IllegalArgumentException("receipt is required");
        if (dispatched && result == null) throw new IllegalArgumentException("dispatched operation requires a result");
        if (!dispatched && result != null) throw new IllegalArgumentException("replayed operation cannot contain a new result");
    }
}
