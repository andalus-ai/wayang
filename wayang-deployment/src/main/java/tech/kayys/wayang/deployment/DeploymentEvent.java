package tech.kayys.wayang.deployment;

import java.time.Instant;
import java.util.Objects;

/** Append-only audit fact for a deployment lifecycle transition. */
public record DeploymentEvent(String deploymentId, long sequence, DeploymentState fromState, DeploymentState toState,
        String actor, String reason, String correlationId, String idempotencyKey, Instant occurredAt) {
    public DeploymentEvent {
        deploymentId = DeploymentSnapshot.required(deploymentId, "deploymentId");
        if (sequence < 0) throw new IllegalArgumentException("sequence must not be negative");
        Objects.requireNonNull(toState, "toState");
        actor = DeploymentSnapshot.required(actor, "actor");
        reason = DeploymentSnapshot.required(reason, "reason");
        correlationId = DeploymentSnapshot.required(correlationId, "correlationId");
        idempotencyKey = DeploymentSnapshot.required(idempotencyKey, "idempotencyKey");
        Objects.requireNonNull(occurredAt, "occurredAt");
    }
}
