package tech.kayys.wayang.deployment;

import java.util.Map;
import java.util.Objects;

/** Immutable, scoped request passed to a trusted deployment target adapter. */
public record DeploymentCommand(String deploymentId, String tenantId, String environment, String targetId,
        String releaseId, String idempotencyKey, Map<String, String> parameters) {
    public DeploymentCommand {
        deploymentId = DeploymentSnapshot.required(deploymentId, "deploymentId");
        tenantId = DeploymentSnapshot.required(tenantId, "tenantId");
        environment = DeploymentSnapshot.required(environment, "environment");
        targetId = DeploymentSnapshot.required(targetId, "targetId");
        releaseId = DeploymentSnapshot.required(releaseId, "releaseId");
        idempotencyKey = DeploymentSnapshot.required(idempotencyKey, "idempotencyKey");
        parameters = Map.copyOf(Objects.requireNonNull(parameters, "parameters"));
    }
}
