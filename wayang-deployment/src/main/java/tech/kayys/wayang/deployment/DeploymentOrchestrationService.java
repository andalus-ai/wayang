package tech.kayys.wayang.deployment;

import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Coordinates lifecycle recording with a trusted target adapter. Adapter implementations must
 * enforce the supplied per-call deadline; cancelling a Java future is not treated as termination.
 * Activation is successful only after HEALTHY readiness. Rollback remains a separate authorized action.
 */
public final class DeploymentOrchestrationService {
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private final DeploymentLedger ledger;
    private final DeploymentLifecycleService lifecycle;
    private final DeploymentTargetRegistry targets;
    private final Clock clock;

    public DeploymentOrchestrationService(DeploymentLedger ledger, DeploymentLifecycleService lifecycle,
            DeploymentTargetRegistry targets, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DeploymentExecutionResult activate(DeploymentCommand command, String actor, String reason,
            String correlationId, Duration timeout) {
        Objects.requireNonNull(command, "command");
        validateTimeout(timeout);
        String safeActor = requiredWithin(actor, "actor", 200);
        String safeReason = requiredWithin(reason, "reason", 2048);
        String correlation = requiredWithin(correlationId, "correlationId", 200);

        DeploymentSnapshot current = ledger.find(command.deploymentId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown deployment: " + command.deploymentId()));
        validateScope(command, current);
        DeploymentTarget target = targets.require(command.targetId());

        DeploymentSnapshot activating = lifecycle.beginActivation(current.deploymentId(), current.version(),
                safeActor, safeReason, correlation, eventKey(command.idempotencyKey(), "begin"));
        TargetOperationResult activation = invokeActivation(target, command, timeout);
        if (activation.status() != TargetOperationResult.Status.SUCCEEDED) {
            DeploymentSnapshot failed = lifecycle.recordHealth(command.deploymentId(), activating.version(),
                    HealthOutcome.UNHEALTHY, safeActor, operationReason("activation failed", activation.detail()),
                    correlation, eventKey(command.idempotencyKey(), "activation-result"));
            return new DeploymentExecutionResult(failed, activation, null);
        }

        HealthCheckResult health = invokeHealthCheck(target, command, timeout);
        DeploymentSnapshot observed = lifecycle.recordHealth(command.deploymentId(), activating.version(),
                health.outcome(), safeActor, healthReason(health), correlation,
                eventKey(command.idempotencyKey(), "health-result"));
        return new DeploymentExecutionResult(observed, activation, health);
    }

    /**
     * Deactivates an ACTIVE or DEGRADED deployment. A successful adapter acknowledgement is
     * recorded as DEACTIVATED; failed or uncertain outcomes are recorded as FAILED.
     */
    public DeploymentTargetOperationResult deactivate(DeploymentCommand command, String actor, String reason,
            String correlationId, Duration timeout) {
        validateOperationRequest(command, actor, reason, correlationId, timeout);
        String safeActor = requiredWithin(actor, "actor", 200);
        String safeReason = requiredWithin(reason, "reason", 2048);
        String correlation = requiredWithin(correlationId, "correlationId", 200);
        DeploymentSnapshot current = requireScopedSnapshot(command);
        DeploymentTarget target = targets.require(command.targetId());
        DeploymentSnapshot deactivating = lifecycle.beginDeactivation(current.deploymentId(), current.version(),
                safeActor, safeReason, correlation, eventKey(command.idempotencyKey(), "deactivate-begin"));
        TargetOperationResult operation = invokeDeactivation(target, command, timeout);
        DeploymentSnapshot result;
        if (operation.status() == TargetOperationResult.Status.SUCCEEDED) {
            result = lifecycle.completeDeactivation(command.deploymentId(), deactivating.version(), safeActor,
                    operationReason("target deactivated", operation.detail()), correlation,
                    eventKey(command.idempotencyKey(), "deactivate-result"));
        } else {
            result = lifecycle.failDeactivation(command.deploymentId(), deactivating.version(), safeActor,
                    operationReason("deactivation failed", operation.detail()), correlation,
                    eventKey(command.idempotencyKey(), "deactivate-result"));
        }
        return new DeploymentTargetOperationResult(result, operation, null);
    }

    /**
     * Rolls back an ACTIVE, DEGRADED, or FAILED deployment. ROLLED_BACK is recorded only when
     * the adapter succeeds and the restored deployment subsequently reports HEALTHY.
     */
    public DeploymentTargetOperationResult rollback(DeploymentCommand command, String actor, String reason,
            String correlationId, Duration timeout) {
        validateOperationRequest(command, actor, reason, correlationId, timeout);
        String safeActor = requiredWithin(actor, "actor", 200);
        String safeReason = requiredWithin(reason, "reason", 2048);
        String correlation = requiredWithin(correlationId, "correlationId", 200);
        DeploymentSnapshot current = requireScopedSnapshot(command);
        DeploymentTarget target = targets.require(command.targetId());
        DeploymentSnapshot rollingBack = lifecycle.beginRollback(current.deploymentId(), current.version(),
                safeActor, safeReason, correlation, eventKey(command.idempotencyKey(), "rollback-begin"));
        TargetOperationResult operation = invokeRollback(target, command, timeout);
        if (operation.status() != TargetOperationResult.Status.SUCCEEDED) {
            DeploymentSnapshot failed = lifecycle.failRollback(command.deploymentId(), rollingBack.version(), safeActor,
                    operationReason("rollback failed", operation.detail()), correlation,
                    eventKey(command.idempotencyKey(), "rollback-result"));
            return new DeploymentTargetOperationResult(failed, operation, null);
        }

        HealthCheckResult health = invokeHealthCheck(target, command, timeout);
        DeploymentSnapshot result;
        if (health.outcome() == HealthOutcome.HEALTHY) {
            result = lifecycle.completeRollback(command.deploymentId(), rollingBack.version(), safeActor,
                    healthReason(health), correlation, eventKey(command.idempotencyKey(), "rollback-result"));
        } else {
            result = lifecycle.failRollback(command.deploymentId(), rollingBack.version(), safeActor,
                    operationReason("rollback readiness not healthy", healthReason(health)), correlation,
                    eventKey(command.idempotencyKey(), "rollback-result"));
        }
        return new DeploymentTargetOperationResult(result, operation, health);
    }

    private void validateOperationRequest(DeploymentCommand command, String actor, String reason,
            String correlationId, Duration timeout) {
        Objects.requireNonNull(command, "command");
        validateTimeout(timeout);
        requiredWithin(actor, "actor", 200);
        requiredWithin(reason, "reason", 2048);
        requiredWithin(correlationId, "correlationId", 200);
    }

    private DeploymentSnapshot requireScopedSnapshot(DeploymentCommand command) {
        DeploymentSnapshot current = ledger.find(command.deploymentId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown deployment: " + command.deploymentId()));
        validateScope(command, current);
        return current;
    }

    private TargetOperationResult invokeDeactivation(DeploymentTarget target, DeploymentCommand command, Duration timeout) {
        try {
            TargetOperationResult result = target.deactivate(command, timeout);
            return result == null ? failedOperation(command, "deactivate", "Target returned no deactivation result") : result;
        } catch (RuntimeException failure) {
            return failedOperation(command, "deactivate", "Target deactivation raised " + failure.getClass().getSimpleName());
        }
    }

    private TargetOperationResult invokeRollback(DeploymentTarget target, DeploymentCommand command, Duration timeout) {
        try {
            TargetOperationResult result = target.rollback(command, timeout);
            return result == null ? failedOperation(command, "rollback", "Target returned no rollback result") : result;
        } catch (RuntimeException failure) {
            return failedOperation(command, "rollback", "Target rollback raised " + failure.getClass().getSimpleName());
        }
    }

    private TargetOperationResult failedOperation(DeploymentCommand command, String operation, String detail) {
        return new TargetOperationResult(TargetOperationResult.Status.FAILED,
                eventKey(command.idempotencyKey(), operation), detail, clock.instant());
    }

    private TargetOperationResult invokeActivation(DeploymentTarget target, DeploymentCommand command, Duration timeout) {
        try {
            TargetOperationResult result = target.activate(command, timeout);
            return result == null ? failedActivation(command, "Target returned no activation result") : result;
        } catch (RuntimeException failure) {
            return failedActivation(command, "Target activation raised " + failure.getClass().getSimpleName());
        }
    }

    private HealthCheckResult invokeHealthCheck(DeploymentTarget target, DeploymentCommand command, Duration timeout) {
        try {
            HealthCheckResult result = target.healthCheck(command, timeout);
            if (result != null) return result;
        } catch (RuntimeException failure) {
            return new HealthCheckResult(HealthOutcome.UNKNOWN, Map.of(),
                    "Target health check raised " + failure.getClass().getSimpleName(), clock.instant());
        }
        return new HealthCheckResult(HealthOutcome.UNKNOWN, Map.of(), "Target returned no health result", clock.instant());
    }

    private TargetOperationResult failedActivation(DeploymentCommand command, String detail) {
        return new TargetOperationResult(TargetOperationResult.Status.FAILED,
                eventKey(command.idempotencyKey(), "activate"), detail, clock.instant());
    }

    private static void validateScope(DeploymentCommand command, DeploymentSnapshot snapshot) {
        if (!snapshot.tenantId().equals(command.tenantId())
                || !snapshot.environment().equals(command.environment())
                || !snapshot.targetId().equals(command.targetId())
                || !snapshot.releaseId().equals(command.releaseId())) {
            throw new SecurityException("Deployment command scope does not match the registered deployment");
        }
    }

    private static void validateTimeout(Duration timeout) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be positive and no greater than " + MAX_TIMEOUT);
        }
    }

    private static String eventKey(String key, String phase) {
        // Stable, bounded database key even when the caller supplies a long idempotency token.
        return java.util.UUID.nameUUIDFromBytes((key + ":" + phase).getBytes(java.nio.charset.StandardCharsets.UTF_8)).toString();
    }
    private static String requiredWithin(String value, String name, int maxLength) {
        String normalized = DeploymentSnapshot.required(value, name);
        if (normalized.length() > maxLength) throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        return normalized;
    }
    private static String operationReason(String prefix, String detail) {
        String value = detail == null || detail.isBlank() ? prefix : prefix + ": " + detail;
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }
    private static String healthReason(HealthCheckResult health) {
        return operationReason("health outcome " + health.outcome(), health.detail());
    }
}
