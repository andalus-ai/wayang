package tech.kayys.wayang.deployment;

import java.time.Duration;
import java.util.Objects;

/**
 * Original trusted request material needed to reconcile a terminal deployment receipt.
 *
 * The command may contain credentials or other sensitive parameters. Persist it only through a
 * {@link DeploymentLifecycleReconciliationContextCodec}; the JDBC store persists only its
 * authenticated-encryption output, never this record as plaintext.
 */
public record DeploymentLifecycleReconciliationContext(
        String operation,
        DeploymentCommand command,
        String actor,
        String reason,
        String correlationId,
        Duration timeout) {
    public DeploymentLifecycleReconciliationContext {
        operation = DeploymentSnapshot.required(operation, "operation");
        if (!operation.equals("ACTIVATE") && !operation.equals("DEACTIVATE") && !operation.equals("ROLLBACK")) {
            throw new IllegalArgumentException("Unsupported deployment operation: " + operation);
        }
        Objects.requireNonNull(command, "command");
        actor = boundedRequired(actor, "actor", 200);
        reason = boundedRequired(reason, "reason", 2048);
        correlationId = boundedRequired(correlationId, "correlationId", 200);
        if (timeout == null || timeout.isZero() || timeout.isNegative()
                || timeout.compareTo(Duration.ofMinutes(10)) > 0) {
            throw new IllegalArgumentException("timeout must be positive and no greater than 10 minutes");
        }
    }

    public String commandFingerprint() {
        return DeploymentCommandFingerprint.of(operation, command);
    }

    private static String boundedRequired(String value, String name, int max) {
        String normalized = DeploymentSnapshot.required(value, name);
        if (normalized.length() > max) throw new IllegalArgumentException(name + " exceeds " + max + " characters");
        return normalized;
    }
}
