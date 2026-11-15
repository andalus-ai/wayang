package tech.kayys.wayang.deployment;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;

/**
 * Reconciles expired operation leases by observing the target. It never re-dispatches a side effect.
 * Concurrent recovery workers may observe the same operation; receipt completion is conditional,
 * so only one worker can commit a terminal result.
 */
public final class DeploymentOperationRecoveryService {
    private static final Duration MAX_TIMEOUT = Duration.ofMinutes(10);
    private final DeploymentLedger ledger;
    private final DeploymentOperationReceiptStore receipts;
    private final DeploymentTargetRegistry targets;
    private final Clock clock;

    public DeploymentOperationRecoveryService(DeploymentLedger ledger, DeploymentOperationReceiptStore receipts,
            DeploymentTargetRegistry targets, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.targets = Objects.requireNonNull(targets, "targets");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DeploymentOperationRecoveryReport reconcileExpired(Duration timeout, int limit) {
        if (timeout == null || timeout.isZero() || timeout.isNegative() || timeout.compareTo(MAX_TIMEOUT) > 0) {
            throw new IllegalArgumentException("timeout must be positive and no greater than " + MAX_TIMEOUT);
        }
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");

        var expired = receipts.findExpiredRunning(clock.instant(), limit);
        int succeeded = 0, failed = 0, running = 0, unknown = 0, conflicted = 0;
        for (DeploymentOperationReceipt receipt : expired) {
            DeploymentOperationObservation observation = observe(receipt, timeout);
            if (observation.status() == DeploymentOperationObservation.Status.IN_PROGRESS) {
                running++;
                continue;
            }
            try {
                switch (observation.status()) {
                    case SUCCEEDED -> {
                        receipts.complete(receipt.deploymentId(), receipt.operationKey(), receipt.commandFingerprint(),
                                DeploymentOperationStatus.SUCCEEDED, "TARGET_CONFIRMED_SUCCESS", observation.detail(), clock.instant());
                        succeeded++;
                    }
                    case FAILED -> {
                        receipts.complete(receipt.deploymentId(), receipt.operationKey(), receipt.commandFingerprint(),
                                DeploymentOperationStatus.FAILED, "TARGET_CONFIRMED_FAILURE", observation.detail(), clock.instant());
                        failed++;
                    }
                    case UNKNOWN -> {
                        receipts.markUnknown(receipt.deploymentId(), receipt.operationKey(),
                                bounded(observation.detail()), clock.instant());
                        unknown++;
                    }
                    case IN_PROGRESS -> running++;
                }
            } catch (IllegalStateException concurrentOrTerminal) {
                // Another recovery worker or an operator won the conditional receipt transition.
                conflicted++;
            }
        }
        return new DeploymentOperationRecoveryReport(expired.size(), succeeded, failed, running, unknown, conflicted);
    }

    private DeploymentOperationObservation observe(DeploymentOperationReceipt receipt, Duration timeout) {
        try {
            DeploymentSnapshot snapshot = ledger.find(receipt.deploymentId()).orElse(null);
            if (snapshot == null) return unknown("Deployment snapshot is unavailable");
            DeploymentTarget target = targets.require(snapshot.targetId());
            DeploymentOperationObservation observation = target.observeOperation(receipt, timeout);
            return observation == null ? unknown("Target returned no operation observation") : observation;
        } catch (RuntimeException failure) {
            // Do not persist adapter exception messages; they may contain credentials or process output.
            return unknown("Target operation observation failed: " + failure.getClass().getSimpleName());
        }
    }

    private DeploymentOperationObservation unknown(String reason) {
        return DeploymentOperationObservation.unknown(reason, clock.instant());
    }

    private static String bounded(String detail) {
        String value = detail == null || detail.isBlank() ? "Target operation outcome is unknown" : detail;
        return value.length() <= 2048 ? value : value.substring(0, 2048);
    }
}
