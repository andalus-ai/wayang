package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeploymentLifecycleReconciliationWorkerTest {
    private static final Instant NOW = Instant.parse("2026-11-16T00:00:00Z");

    @Test
    void reconcilesSuccessfulActivationWithoutRedispatchAndIsIdempotent() {
        var ledger = installedLedger();
        var lifecycle = new DeploymentLifecycleService(ledger, clock());
        lifecycle.beginActivation("dep-1", 0, "operator", "activation requested", "corr-1", "begin-activation");
        var command = command("op-1");
        var receipt = receipt(command, DeploymentOperationStatus.SUCCEEDED);
        var targetCalls = new AtomicInteger();
        var healthCalls = new AtomicInteger();
        var targets = new DeploymentTargetRegistry();
        targets.register("target-a", target(targetCalls, healthCalls));
        var work = new FakeWorkStore();
        var worker = worker(new FakeReceiptStore(receipt), context(command), work,
                new DeploymentLifecycleReconciliationService(ledger, lifecycle, targets, clock()));

        var first = worker.runOnce(10);
        var second = worker.runOnce(10);

        assertEquals(1, first.reconciled());
        assertEquals(1, first.claimed());
        assertEquals(1, second.skipped());
        assertEquals(0, targetCalls.get());
        assertEquals(1, healthCalls.get());
        assertEquals(DeploymentState.ACTIVE, ledger.find("dep-1").orElseThrow().state());
    }

    @Test
    void unknownReceiptIsRecordedForOperatorResolutionWithoutTargetCalls() {
        var ledger = installedLedger();
        var lifecycle = new DeploymentLifecycleService(ledger, clock());
        lifecycle.beginActivation("dep-1", 0, "operator", "activation requested", "corr-1", "begin-activation");
        var command = command("op-unknown");
        var receipt = receipt(command, DeploymentOperationStatus.UNKNOWN);
        var targetCalls = new AtomicInteger();
        var healthCalls = new AtomicInteger();
        var targets = new DeploymentTargetRegistry();
        targets.register("target-a", target(targetCalls, healthCalls));
        var work = new FakeWorkStore();
        var worker = worker(new FakeReceiptStore(receipt), context(command), work,
                new DeploymentLifecycleReconciliationService(ledger, lifecycle, targets, clock()));

        var report = worker.runOnce(10);

        assertEquals(1, report.unresolved());
        assertEquals(DeploymentLifecycleReconciliationWorkStatus.UNRESOLVED, work.status(receipt));
        assertEquals(0, targetCalls.get());
        assertEquals(0, healthCalls.get());
        assertEquals(DeploymentState.ACTIVATING, ledger.find("dep-1").orElseThrow().state());
    }

    @Test
    void missingContextIsRetriedWithoutAdvancingLifecycle() {
        var ledger = installedLedger();
        var lifecycle = new DeploymentLifecycleService(ledger, clock());
        lifecycle.beginActivation("dep-1", 0, "operator", "activation requested", "corr-1", "begin-activation");
        var command = command("op-missing-context");
        var receipt = receipt(command, DeploymentOperationStatus.SUCCEEDED);
        var work = new FakeWorkStore();
        var worker = worker(new FakeReceiptStore(receipt), ignored -> Optional.empty(), work,
                new DeploymentLifecycleReconciliationService(ledger, lifecycle, new DeploymentTargetRegistry(), clock()));

        var report = worker.runOnce(10);

        assertEquals(1, report.waiting());
        assertEquals(DeploymentLifecycleReconciliationWorkStatus.WAITING, work.status(receipt));
        assertEquals(DeploymentState.ACTIVATING, ledger.find("dep-1").orElseThrow().state());
    }

    private static DeploymentLifecycleReconciliationWorker worker(DeploymentOperationReceiptStore receipts,
            DeploymentLifecycleReconciliationContextStore contexts, FakeWorkStore work,
            DeploymentLifecycleReconciliationService reconciliation) {
        return new DeploymentLifecycleReconciliationWorker(receipts, contexts, work, reconciliation,
                clock(), Duration.ofMinutes(2), Duration.ofSeconds(30));
    }

    private static InMemoryDeploymentLedger installedLedger() {
        var ledger = new InMemoryDeploymentLedger();
        new DeploymentLifecycleService(ledger, clock()).registerInstalled("dep-1", "tenant-a", "prod",
                "target-a", "release-1", "operator", "installed", "corr-install", "install");
        return ledger;
    }

    private static DeploymentCommand command(String key) {
        return new DeploymentCommand("dep-1", "tenant-a", "prod", "target-a", "release-1", key, Map.of());
    }

    private static DeploymentOperationReceipt receipt(DeploymentCommand command, DeploymentOperationStatus status) {
        return new DeploymentOperationReceipt(command.deploymentId(), command.idempotencyKey(),
                DeploymentCommandFingerprint.of("ACTIVATE", command), "ACTIVATE", status, 1,
                "target-" + command.idempotencyKey(), status.name(), "target result", null, NOW, NOW);
    }

    private static DeploymentLifecycleReconciliationContextStore context(DeploymentCommand command) {
        var context = new DeploymentLifecycleReconciliationContext("ACTIVATE", command, "operator",
                "activation requested", "corr-1", Duration.ofSeconds(20));
        return new DeploymentLifecycleReconciliationContextStore() {
            @Override public void save(DeploymentLifecycleReconciliationContext ignored) { }
            @Override public Optional<DeploymentLifecycleReconciliationContext> find(DeploymentOperationReceipt receipt) {
                return receipt.commandFingerprint().equals(context.commandFingerprint())
                        ? Optional.of(context) : Optional.empty();
            }
        };
    }

    private static DeploymentTarget target(AtomicInteger operationCalls, AtomicInteger healthCalls) {
        return new DeploymentTarget() {
            @Override public TargetOperationResult activate(DeploymentCommand command, Duration timeout) {
                operationCalls.incrementAndGet();
                throw new AssertionError("reconciliation worker must never dispatch target operations");
            }
            @Override public TargetOperationResult deactivate(DeploymentCommand command, Duration timeout) {
                operationCalls.incrementAndGet();
                throw new AssertionError("reconciliation worker must never dispatch target operations");
            }
            @Override public TargetOperationResult rollback(DeploymentCommand command, Duration timeout) {
                operationCalls.incrementAndGet();
                throw new AssertionError("reconciliation worker must never dispatch target operations");
            }
            @Override public HealthCheckResult healthCheck(DeploymentCommand command, Duration timeout) {
                healthCalls.incrementAndGet();
                return new HealthCheckResult(HealthOutcome.HEALTHY, Map.of("ready", true), "healthy", NOW);
            }
        };
    }

    private static Clock clock() { return Clock.fixed(NOW, ZoneOffset.UTC); }

    private static final class FakeReceiptStore implements DeploymentOperationReceiptStore {
        private final DeploymentOperationReceipt receipt;
        private FakeReceiptStore(DeploymentOperationReceipt receipt) { this.receipt = receipt; }
        @Override public DeploymentOperationReceipt claim(String d, String k, String f, String o, String t, Instant n, Instant l) {
            throw new UnsupportedOperationException();
        }
        @Override public DeploymentOperationClaim claimExclusive(String d, String k, String f, String o,
                String t, Instant n, Instant l) { throw new UnsupportedOperationException(); }
        @Override public DeploymentOperationReceipt complete(String d, String k, String f,
                DeploymentOperationStatus s, String c, String detail, Instant now) {
            throw new UnsupportedOperationException();
        }
        @Override public Optional<DeploymentOperationReceipt> find(String d, String k) {
            return Optional.of(receipt);
        }
        @Override public List<DeploymentOperationReceipt> findExpiredRunning(Instant now, int limit) {
            return List.of();
        }
        @Override public List<DeploymentOperationReceipt> findTerminalReceipts(int limit) {
            return List.of(receipt).subList(0, Math.min(1, limit));
        }
        @Override public DeploymentOperationReceipt markUnknown(String d, String k, String reason, Instant now) {
            throw new UnsupportedOperationException();
        }
    }

    private static final class FakeWorkStore implements DeploymentLifecycleReconciliationWorkStore {
        private final Map<String, DeploymentLifecycleReconciliationWorkStatus> statuses = new HashMap<>();
        @Override public synchronized boolean claim(DeploymentOperationReceipt receipt, Instant now, Instant leaseUntil) {
            String key = key(receipt);
            var status = statuses.get(key);
            if (status == null || status == DeploymentLifecycleReconciliationWorkStatus.WAITING) {
                statuses.put(key, DeploymentLifecycleReconciliationWorkStatus.CLAIMED);
                return true;
            }
            return false;
        }
        @Override public synchronized void complete(DeploymentOperationReceipt receipt,
                DeploymentLifecycleReconciliationWorkStatus status, String detail, Instant now, Instant retryAt) {
            if (statuses.get(key(receipt)) != DeploymentLifecycleReconciliationWorkStatus.CLAIMED) {
                throw new IllegalStateException("not claimed");
            }
            statuses.put(key(receipt), status);
        }
        synchronized DeploymentLifecycleReconciliationWorkStatus status(DeploymentOperationReceipt receipt) {
            return statuses.get(key(receipt));
        }
        private static String key(DeploymentOperationReceipt receipt) {
            return receipt.deploymentId() + "/" + receipt.operationKey();
        }
    }
}
