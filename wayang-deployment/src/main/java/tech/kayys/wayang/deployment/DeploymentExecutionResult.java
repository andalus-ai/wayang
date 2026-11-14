package tech.kayys.wayang.deployment;

/** Result of one activation attempt; healthCheck is null when activation did not succeed. */
public record DeploymentExecutionResult(DeploymentSnapshot snapshot, TargetOperationResult activation,
        HealthCheckResult healthCheck) {
    public DeploymentExecutionResult {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        if (activation == null) throw new IllegalArgumentException("activation result is required");
        if (healthCheck != null && activation.status() != TargetOperationResult.Status.SUCCEEDED) {
            throw new IllegalArgumentException("health check cannot exist when activation did not succeed");
        }
    }
}
