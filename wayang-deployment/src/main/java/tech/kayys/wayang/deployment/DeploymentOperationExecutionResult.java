package tech.kayys.wayang.deployment;

/**
 * Outcome of passing through the durable dispatch gate.
 * A non-dispatched result carries the existing receipt and never contains a fabricated operation result.
 */
public record DeploymentOperationExecutionResult<T>(
        boolean dispatched,
        T result,
        DeploymentOperationReceipt receipt) {
    public DeploymentOperationExecutionResult {
        if (receipt == null) throw new IllegalArgumentException("receipt is required");
        if (dispatched && result == null) throw new IllegalArgumentException("a dispatched operation must return a result");
        if (!dispatched && result != null) throw new IllegalArgumentException("a replay must not contain a new result");
    }
}
