package tech.kayys.wayang.deployment;

import java.time.Instant;

/** Read-only observation of an external operation during crash recovery. */
public record DeploymentOperationObservation(Status status, String detail, Instant observedAt) {
    public enum Status { IN_PROGRESS, SUCCEEDED, FAILED, UNKNOWN }

    public DeploymentOperationObservation {
        if (status == null) throw new IllegalArgumentException("status is required");
        if (detail != null && detail.length() > 2048) throw new IllegalArgumentException("detail exceeds 2048 characters");
        if (observedAt == null) throw new IllegalArgumentException("observedAt is required");
    }

    public static DeploymentOperationObservation unknown(String detail, Instant at) {
        return new DeploymentOperationObservation(Status.UNKNOWN, detail, at);
    }
}
