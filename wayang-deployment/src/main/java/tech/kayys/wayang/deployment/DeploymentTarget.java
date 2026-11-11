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
}
