package tech.kayys.wayang.deployment;

import java.time.Instant;
import java.util.Objects;

/** Immutable current view. Version increases exactly once for every accepted lifecycle event. */
public record DeploymentSnapshot(String deploymentId, String tenantId, String environment, String targetId,
        String releaseId, DeploymentState state, long version, Instant updatedAt, String lastReason) {
    public DeploymentSnapshot {
        deploymentId = required(deploymentId, "deploymentId");
        tenantId = required(tenantId, "tenantId");
        environment = required(environment, "environment");
        targetId = required(targetId, "targetId");
        releaseId = required(releaseId, "releaseId");
        Objects.requireNonNull(state, "state");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (version < 0) throw new IllegalArgumentException("version must not be negative");
    }
    static String required(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        return value.trim();
    }
    DeploymentSnapshot advance(DeploymentState next, Instant at, String reason) {
        return new DeploymentSnapshot(deploymentId, tenantId, environment, targetId, releaseId, next, version + 1, at, reason);
    }
}
