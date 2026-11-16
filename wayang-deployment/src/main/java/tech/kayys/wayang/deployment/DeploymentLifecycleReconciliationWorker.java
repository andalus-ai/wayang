package tech.kayys.wayang.deployment;

import java.time.Clock;
import java.time.Duration;
import java.util.List;
import java.util.Objects;

/**
 * Bounded, restart-safe worker that applies terminal receipts to lifecycle state.
 *
 * It never calls a deployment target operation. The only target interaction is the reconciliation
 * service's read-only health observation for successful activation and rollback receipts.
 */
public final class DeploymentLifecycleReconciliationWorker {
    private static final Duration MAX_LEASE = Duration.ofMinutes(15);
    private static final Duration MAX_RETRY_DELAY = Duration.ofHours(1);

    private final DeploymentOperationReceiptStore receipts;
    private final DeploymentLifecycleReconciliationContextStore contexts;
    private final DeploymentLifecycleReconciliationWorkStore work;
    private final DeploymentLifecycleReconciliationService reconciliation;
    private final Clock clock;
    private final Duration lease;
    private final Duration retryDelay;

    public DeploymentLifecycleReconciliationWorker(DeploymentOperationReceiptStore receipts,
            DeploymentLifecycleReconciliationContextStore contexts,
            DeploymentLifecycleReconciliationWorkStore work,
            DeploymentLifecycleReconciliationService reconciliation,
            Clock clock, Duration lease, Duration retryDelay) {
        this.receipts = Objects.requireNonNull(receipts, "receipts");
        this.contexts = Objects.requireNonNull(contexts, "contexts");
        this.work = Objects.requireNonNull(work, "work");
        this.reconciliation = Objects.requireNonNull(reconciliation, "reconciliation");
        this.clock = Objects.requireNonNull(clock, "clock");
        if (lease == null || lease.isZero() || lease.isNegative() || lease.compareTo(MAX_LEASE) > 0) {
            throw new IllegalArgumentException("lease must be positive and no greater than " + MAX_LEASE);
        }
        if (retryDelay == null || retryDelay.isNegative() || retryDelay.compareTo(MAX_RETRY_DELAY) > 0) {
            throw new IllegalArgumentException("retryDelay must be between zero and " + MAX_RETRY_DELAY);
        }
        this.lease = lease;
        this.retryDelay = retryDelay;
    }

    /** Processes at most {@code limit} terminal receipts; callers own scheduling and back-pressure. */
    public DeploymentLifecycleReconciliationWorkerResult runOnce(int limit) {
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be between 1 and 1000");
        List<DeploymentOperationReceipt> candidates = receipts.findTerminalReceipts(limit);
        int claimed = 0;
        int reconciled = 0;
        int waiting = 0;
        int unresolved = 0;
        int conflicts = 0;
        int skipped = 0;
        for (DeploymentOperationReceipt receipt : candidates) {
            var now = clock.instant();
            if (!work.claim(receipt, now, now.plus(lease))) {
                skipped++;
                continue;
            }
            claimed++;
            DeploymentLifecycleReconciliationContext context;
            try {
                context = contexts.find(receipt).orElse(null);
            } catch (RuntimeException unavailable) {
                scheduleWaiting(receipt, "Reconciliation context is temporarily unavailable");
                waiting++;
                continue;
            }
            if (context == null) {
                scheduleWaiting(receipt, "No matching durable reconciliation context is available");
                waiting++;
                continue;
            }
            if (!context.commandFingerprint().equals(receipt.commandFingerprint())
                    || !context.operation().equals(receipt.operation())
                    || !context.command().deploymentId().equals(receipt.deploymentId())
                    || !context.command().idempotencyKey().equals(receipt.operationKey())) {
                finish(receipt, DeploymentLifecycleReconciliationWorkStatus.CONFLICT,
                        "Reconciliation context identity does not match receipt", null);
                conflicts++;
                continue;
            }
            try {
                DeploymentLifecycleReconciliationResult result = reconciliation.reconcile(receipt,
                        context.command(), context.actor(), context.reason(), context.correlationId(), context.timeout());
                switch (result.status()) {
                    case RECONCILED -> {
                        finish(receipt, DeploymentLifecycleReconciliationWorkStatus.RECONCILED,
                                result.detail(), null);
                        reconciled++;
                    }
                    case ALREADY_RECONCILED -> {
                        finish(receipt, DeploymentLifecycleReconciliationWorkStatus.ALREADY_RECONCILED,
                                result.detail(), null);
                        reconciled++;
                    }
                    case NOT_APPLICABLE -> finish(receipt,
                            DeploymentLifecycleReconciliationWorkStatus.NOT_APPLICABLE, result.detail(), null);
                    case WAITING_FOR_HEALTH -> {
                        scheduleWaiting(receipt, result.detail());
                        waiting++;
                    }
                    case UNRESOLVED -> {
                        finish(receipt, DeploymentLifecycleReconciliationWorkStatus.UNRESOLVED,
                                result.detail(), null);
                        unresolved++;
                    }
                    case CONFLICT -> {
                        finish(receipt, DeploymentLifecycleReconciliationWorkStatus.CONFLICT,
                                result.detail(), null);
                        conflicts++;
                    }
                }
            } catch (SecurityException | IllegalArgumentException invalid) {
                finish(receipt, DeploymentLifecycleReconciliationWorkStatus.CONFLICT,
                        "Reconciliation rejected due to invalid identity or lifecycle state", null);
                conflicts++;
            } catch (RuntimeException unavailable) {
                // Avoid persisting exception messages: adapters and database drivers may expose secrets.
                scheduleWaiting(receipt, "Reconciliation attempt failed; retry scheduled");
                waiting++;
            }
        }
        return new DeploymentLifecycleReconciliationWorkerResult(candidates.size(), claimed,
                reconciled, waiting, unresolved, conflicts, skipped);
    }

    private void scheduleWaiting(DeploymentOperationReceipt receipt, String detail) {
        finish(receipt, DeploymentLifecycleReconciliationWorkStatus.WAITING, detail,
                clock.instant().plus(retryDelay));
    }

    private void finish(DeploymentOperationReceipt receipt, DeploymentLifecycleReconciliationWorkStatus status,
            String detail, java.time.Instant retryAt) {
        work.complete(receipt, status, detail, clock.instant(), retryAt);
    }
}
