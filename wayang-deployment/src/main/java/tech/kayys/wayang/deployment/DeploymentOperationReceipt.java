package tech.kayys.wayang.deployment;

import java.time.Instant;
import java.util.Objects;

/** Durable idempotency receipt for one externally observable deployment operation. */
public record DeploymentOperationReceipt(
        String deploymentId,
        String operationKey,
        String commandFingerprint,
        String operation,
        DeploymentOperationStatus status,
        int attempt,
        String targetOperationKey,
        String resultCode,
        String resultDetail,
        Instant leaseUntil,
        Instant createdAt,
        Instant updatedAt) {
    public DeploymentOperationReceipt {
        deploymentId = DeploymentSnapshot.required(deploymentId, "deploymentId");
        operationKey = DeploymentSnapshot.required(operationKey, "operationKey");
        commandFingerprint = DeploymentSnapshot.required(commandFingerprint, "commandFingerprint");
        operation = DeploymentSnapshot.required(operation, "operation");
        Objects.requireNonNull(status, "status");
        if (attempt < 0) throw new IllegalArgumentException("attempt must not be negative");
        Objects.requireNonNull(createdAt, "createdAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (resultDetail != null && resultDetail.length() > 2048) {
            throw new IllegalArgumentException("resultDetail exceeds 2048 characters");
        }
    }

    public boolean terminal() {
        return status == DeploymentOperationStatus.SUCCEEDED
                || status == DeploymentOperationStatus.FAILED
                || status == DeploymentOperationStatus.UNKNOWN;
    }
}
