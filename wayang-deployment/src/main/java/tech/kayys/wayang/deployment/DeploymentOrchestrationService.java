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
        String safeActor = DeploymentSnapshot.required(actor, "actor");
        String safeReason = DeploymentSnapshot.required(reason, "reason");
        String correlation = DeploymentSnapshot.required(correlationId, "correlationId");

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
                command.idempotencyKey() + ":activate", detail, clock.instant());
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

    private static String eventKey(String key, String phase) { return key + ":" + phase; }
    private static String operationReason(String prefix, String detail) {
        return detail == null || detail.isBlank() ? prefix : prefix + ": " + detail;
    }
    private static String healthReason(HealthCheckResult health) {
        return operationReason("health outcome " + health.outcome(), health.detail());
    }
}
