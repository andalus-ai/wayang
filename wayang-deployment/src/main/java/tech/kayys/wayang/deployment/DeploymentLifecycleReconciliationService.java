package tech.kayys.wayang.deployment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.util.Map;
import java.util.Objects;

/**
 * Applies an already-terminal operation receipt to its pending lifecycle transition.
 *
 * This service never dispatches or retries a deployment side effect. The caller must reload the
 * original command and audit context from its trusted durable request store; receipts intentionally
 * do not contain secrets or enough information to reconstruct arbitrary command parameters.
 */
public final class DeploymentLifecycleReconciliationService {
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);

    private final DeploymentLedger ledger;
    private final DeploymentLifecycleService lifecycle;
    private final DeploymentTargetRegistry targets;
    private final Clock clock;

    public DeploymentLifecycleReconciliationService(DeploymentLedger ledger,
            DeploymentLifecycleService lifecycle, DeploymentTargetRegistry targets, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.lifecycle = Objects.requireNonNull(lifecycle, "lifecycle");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Reconciles one receipt using the original, trusted request context.
     *
     * The command fingerprint and scope are revalidated before any lifecycle write. UNKNOWN and
     * non-terminal receipts remain unresolved. Activation and rollback require a fresh HEALTHY
     * readiness observation before their successful terminal lifecycle state is recorded.
     */
    public DeploymentLifecycleReconciliationResult reconcile(DeploymentOperationReceipt receipt,
            DeploymentCommand command, String actor, String reason, String correlationId, Duration timeout) {
        Objects.requireNonNull(receipt, "receipt");
        Objects.requireNonNull(command, "command");
        String safeActor = requiredWithin(actor, "actor", 200);
        String safeReason = requiredWithin(reason, "reason", 2048);
        String correlation = requiredWithin(correlationId, "correlationId", 200);
        validateTimeout(timeout);

        if (!receipt.deploymentId().equals(command.deploymentId())
                || !receipt.operationKey().equals(command.idempotencyKey())) {
            throw new SecurityException("Receipt identity does not match the supplied command");
        }
        String operation = receipt.operation();
        if (!operation.equals("ACTIVATE") && !operation.equals("DEACTIVATE") && !operation.equals("ROLLBACK")) {
            throw new IllegalArgumentException("Unsupported deployment operation: " + operation);
        }
        if (!receipt.commandFingerprint().equals(DeploymentCommandFingerprint.of(operation, command))) {
            throw new SecurityException("Receipt fingerprint does not match the supplied command");
        }

        DeploymentSnapshot current = ledger.find(command.deploymentId())
                .orElseThrow(() -> new IllegalArgumentException("Unknown deployment: " + command.deploymentId()));
        validateScope(command, current);

        if (receipt.status() == DeploymentOperationStatus.UNKNOWN
                || receipt.status() == DeploymentOperationStatus.RUNNING
                || receipt.status() == DeploymentOperationStatus.ACCEPTED) {
            return result(DeploymentLifecycleReconciliationResult.Status.UNRESOLVED, current,
                    "Receipt is not a definitive terminal outcome");
        }
        if (receipt.status() == DeploymentOperationStatus.FAILED
                && "PRE_DISPATCH_REJECTED".equals(receipt.resultCode())) {
            return result(DeploymentLifecycleReconciliationResult.Status.NOT_APPLICABLE, current,
                    "Operation was rejected before target dispatch");
        }

        DeploymentState pending = pendingState(operation);
        if (current.state() != pending) {
            if (isFinalState(operation, current.state())) {
                return result(DeploymentLifecycleReconciliationResult.Status.ALREADY_RECONCILED, current,
                        "Lifecycle already reflects a terminal outcome");
            }
            return result(DeploymentLifecycleReconciliationResult.Status.CONFLICT, current,
                    "Lifecycle state " + current.state() + " is not pending " + operation);
        }

        if (receipt.status() == DeploymentOperationStatus.FAILED) {
            DeploymentSnapshot failed = fail(operation, current, safeActor, safeReason, correlation,
                    eventKey(command.idempotencyKey(), resultPhase(operation)));
            return result(DeploymentLifecycleReconciliationResult.Status.RECONCILED, failed,
                    "Definitive target failure applied to lifecycle");
        }

        if (receipt.status() != DeploymentOperationStatus.SUCCEEDED) {
            return result(DeploymentLifecycleReconciliationResult.Status.UNRESOLVED, current,
                    "Receipt status is not eligible for lifecycle reconciliation");
        }

        if (operation.equals("DEACTIVATE")) {
            DeploymentSnapshot deactivated = lifecycle.completeDeactivation(current.deploymentId(),
                    current.version(), safeActor, safeReason, correlation,
                    eventKey(command.idempotencyKey(), "deactivate-result"));
            return result(DeploymentLifecycleReconciliationResult.Status.RECONCILED, deactivated,
                    "Successful deactivation applied to lifecycle");
        }

        HealthCheckResult health = observeHealth(command, timeout);
        if (health == null) {
            return result(DeploymentLifecycleReconciliationResult.Status.WAITING_FOR_HEALTH, current,
                    "A trustworthy readiness observation is not available");
        }
        String healthReason = bounded("health outcome " + health.outcome() + ": " + safeDetail(health.detail()));
        DeploymentSnapshot updated;
        if (operation.equals("ACTIVATE")) {
            updated = lifecycle.recordHealth(current.deploymentId(), current.version(), health.outcome(),
                    safeActor, healthReason, correlation, eventKey(command.idempotencyKey(), "health-result"));
        } else if (health.outcome() == HealthOutcome.HEALTHY) {
            updated = lifecycle.completeRollback(current.deploymentId(), current.version(), safeActor,
                    healthReason, correlation, eventKey(command.idempotencyKey(), "rollback-result"));
        } else {
            updated = lifecycle.failRollback(current.deploymentId(), current.version(), safeActor,
                    bounded("rollback readiness not healthy: " + healthReason), correlation,
                    eventKey(command.idempotencyKey(), "rollback-result"));
        }
        return result(DeploymentLifecycleReconciliationResult.Status.RECONCILED, updated,
                "Terminal target outcome and readiness observation applied to lifecycle");
    }

    private HealthCheckResult observeHealth(DeploymentCommand command, Duration timeout) {
        try {
            DeploymentTarget target = targets.require(command.targetId());
            HealthCheckResult health = target.healthCheck(command, timeout);
            if (health == null) return null;
            return health;
        } catch (RuntimeException unavailable) {
            // Do not persist adapter exception text; it may contain credentials or process output.
            return null;
        }
    }

    private DeploymentSnapshot fail(String operation, DeploymentSnapshot current, String actor,
            String reason, String correlation, String eventKey) {
        return switch (operation) {
            case "ACTIVATE" -> lifecycle.recordHealth(current.deploymentId(), current.version(),
                    HealthOutcome.UNHEALTHY, actor, bounded("activation failed: " + reason), correlation, eventKey);
            case "DEACTIVATE" -> lifecycle.failDeactivation(current.deploymentId(), current.version(),
                    actor, reason, correlation, eventKey);
            case "ROLLBACK" -> lifecycle.failRollback(current.deploymentId(), current.version(),
                    actor, reason, correlation, eventKey);
            default -> throw new IllegalArgumentException("Unsupported deployment operation: " + operation);
        };
    }

    private static DeploymentState pendingState(String operation) {
        return switch (operation) {
            case "ACTIVATE" -> DeploymentState.ACTIVATING;
            case "DEACTIVATE" -> DeploymentState.DEACTIVATING;
            case "ROLLBACK" -> DeploymentState.ROLLING_BACK;
            default -> throw new IllegalArgumentException("Unsupported deployment operation: " + operation);
        };
    }

    private static boolean isFinalState(String operation, DeploymentState state) {
        return switch (operation) {
            case "ACTIVATE" -> state == DeploymentState.ACTIVE || state == DeploymentState.DEGRADED
                    || state == DeploymentState.FAILED;
            case "DEACTIVATE" -> state == DeploymentState.DEACTIVATED || state == DeploymentState.FAILED;
            case "ROLLBACK" -> state == DeploymentState.ROLLED_BACK || state == DeploymentState.FAILED;
            default -> false;
        };
    }

    private static String resultPhase(String operation) {
        return switch (operation) {
            case "ACTIVATE" -> "activation-result";
            case "DEACTIVATE" -> "deactivate-result";
            case "ROLLBACK" -> "rollback-result";
            default -> throw new IllegalArgumentException("Unsupported deployment operation: " + operation);
        };
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

    private static String requiredWithin(String value, String name, int max) {
        String normalized = DeploymentSnapshot.required(value, name);
        if (normalized.length() > max) throw new IllegalArgumentException(name + " exceeds " + max + " characters");
        return normalized;
    }

    private static String safeDetail(String detail) {
        return detail == null || detail.isBlank() ? "no detail" : detail;
    }

    private static String bounded(String value) {
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }

    private static DeploymentLifecycleReconciliationResult result(
            DeploymentLifecycleReconciliationResult.Status status, DeploymentSnapshot snapshot, String detail) {
        return new DeploymentLifecycleReconciliationResult(status, snapshot, bounded(detail));
    }

    private static String eventKey(String key, String phase) {
        try {
            byte[] digest = MessageDigest.getInstance("SHA-256")
                    .digest((key + ":" + phase).getBytes(StandardCharsets.UTF_8));
            return java.util.HexFormat.of().formatHex(digest);
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }
}
