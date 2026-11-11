package tech.kayys.wayang.deployment;

import java.time.Instant;

/** Structured result of a bounded target operation; arbitrary process output is not required. */
public record TargetOperationResult(Status status, String operationId, String detail, Instant completedAt) {
    public enum Status { SUCCEEDED, REJECTED, TIMED_OUT, FAILED, UNKNOWN }
    public TargetOperationResult {
        if (status == null) throw new IllegalArgumentException("status is required");
        operationId = DeploymentSnapshot.required(operationId, "operationId");
        if (detail != null && detail.length() > 2048) throw new IllegalArgumentException("detail exceeds 2048 characters");
        if (completedAt == null) throw new IllegalArgumentException("completedAt is required");
    }
}
