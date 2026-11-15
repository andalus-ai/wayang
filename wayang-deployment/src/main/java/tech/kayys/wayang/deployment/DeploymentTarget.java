package tech.kayys.wayang.deployment;

import java.time.Duration;

/**
 * Capability boundary implemented by a deployment environment adapter.
 * Implementations must enforce the supplied deadline and must never interpret user input as shell.
 */
public interface DeploymentTarget {
    TargetOperationResult activate(DeploymentCommand command, Duration timeout);
    TargetOperationResult deactivate(DeploymentCommand command, Duration timeout);
    TargetOperationResult rollback(DeploymentCommand command, Duration timeout);
    HealthCheckResult healthCheck(DeploymentCommand command, Duration timeout);

    /**
     * Read-only lookup of a previously dispatched operation. The default is deliberately UNKNOWN:
     * adapters that cannot prove the outcome must not cause Wayang to replay the side effect.
     * Implementations should query using receipt.targetOperationKey(), not dispatch a new command.
     */
    default DeploymentOperationObservation observeOperation(DeploymentOperationReceipt receipt, Duration timeout) {
        return DeploymentOperationObservation.unknown("Target does not support operation observation",
                java.time.Instant.now());
    }
}
