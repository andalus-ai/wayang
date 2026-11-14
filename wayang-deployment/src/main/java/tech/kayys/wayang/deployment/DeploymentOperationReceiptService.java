package tech.kayys.wayang.deployment;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/** Validates and coordinates durable operation receipts without executing target side effects. */
public final class DeploymentOperationReceiptService {
    private final DeploymentOperationReceiptStore store;
    private final Clock clock;

    public DeploymentOperationReceiptService(DeploymentOperationReceiptStore store, Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DeploymentOperationReceipt claim(String operation, DeploymentCommand command, Duration lease) {
        Objects.requireNonNull(command, "command");
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(Duration.ofMinutes(15)) > 0) {
            throw new IllegalArgumentException("lease must be positive and no greater than 15 minutes");
        }
        String safeOperation = boundedRequired(operation, "operation", 40);
        boundedRequired(command.deploymentId(), "deploymentId", 160);
        boundedRequired(command.idempotencyKey(), "idempotencyKey", 200);
        String fingerprint = DeploymentCommandFingerprint.of(safeOperation, command);
        var now = clock.instant();
        return store.claim(command.deploymentId(), command.idempotencyKey(), fingerprint,
                safeOperation, command.idempotencyKey(), now, now.plus(lease));
    }

    public DeploymentOperationReceipt complete(String operation, DeploymentCommand command,
            DeploymentOperationStatus status, String resultCode, String resultDetail) {
        if (status != DeploymentOperationStatus.SUCCEEDED && status != DeploymentOperationStatus.FAILED) {
            throw new IllegalArgumentException("completion status must be SUCCEEDED or FAILED");
        }
        String safeOperation = boundedRequired(operation, "operation", 40);
        boundedRequired(command.deploymentId(), "deploymentId", 160);
        boundedRequired(command.idempotencyKey(), "idempotencyKey", 200);
        return store.complete(command.deploymentId(), command.idempotencyKey(),
                DeploymentCommandFingerprint.of(safeOperation, command), status, resultCode, resultDetail, clock.instant());
    }

    public DeploymentOperationReceipt markUnknown(DeploymentCommand command, String reason) {
        return store.markUnknown(command.deploymentId(), command.idempotencyKey(), reason, clock.instant());
    }

    private static String boundedRequired(String value, String name, int maxLength) {
        String normalized = DeploymentSnapshot.required(value, name);
        if (normalized.length() > maxLength) throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
        return normalized;
    }
}
