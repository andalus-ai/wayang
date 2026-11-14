package tech.kayys.wayang.deployment;

/** Result of deactivation or rollback, optionally accompanied by a post-operation health observation. */
public record DeploymentTargetOperationResult(DeploymentSnapshot snapshot, TargetOperationResult operation,
        HealthCheckResult healthCheck) {
    public DeploymentTargetOperationResult {
        if (snapshot == null) throw new IllegalArgumentException("snapshot is required");
        if (operation == null) throw new IllegalArgumentException("operation result is required");
        if (healthCheck != null && operation.status() != TargetOperationResult.Status.SUCCEEDED) {
            throw new IllegalArgumentException("health check cannot exist when operation did not succeed");
        }
    }
}
