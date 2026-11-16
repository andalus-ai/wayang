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
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

class DeploymentOrchestrationReceiptIntegrationTest {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");
    private InMemoryDeploymentLedger ledger;
    private DeploymentLifecycleService lifecycle;
    private DeploymentTargetRegistry targets;
    private ReceiptStore receipts;
    private DeploymentOrchestrationService orchestration;

    @BeforeEach
    void setUp() {
        ledger = new InMemoryDeploymentLedger();
        Clock clock = Clock.fixed(NOW, ZoneOffset.UTC);
        lifecycle = new DeploymentLifecycleService(ledger, clock);
        lifecycle.registerInstalled("dep-1", "tenant-a", "prod", "target-a", "release-1",
                "publisher", "installed", "corr-install", "install-1");
        targets = new DeploymentTargetRegistry();
        receipts = new ReceiptStore();
        orchestration = new DeploymentOrchestrationService(ledger, lifecycle, targets, clock,
                new DeploymentOperationReceiptService(receipts, clock));
    }

    @Test
    void persistsReconciliationContextBeforeTargetDispatch() {
        var contextStore = new InMemoryContextStore();
        var receiptService = new DeploymentOperationReceiptService(receipts, Clock.fixed(NOW, ZoneOffset.UTC));
        var withContext = new DeploymentOrchestrationService(ledger, lifecycle, targets,
                Clock.fixed(NOW, ZoneOffset.UTC), receiptService, contextStore);
        var dispatchedWithContext = new java.util.concurrent.atomic.AtomicBoolean();
        targets.register("target-a", new DeploymentTarget() {
            @Override public TargetOperationResult activate(DeploymentCommand command, Duration timeout) {
                var receipt = receipts.find(command.deploymentId(), command.idempotencyKey()).orElseThrow();
                dispatchedWithContext.set(contextStore.find(receipt).isPresent());
                return new TargetOperationResult(TargetOperationResult.Status.SUCCEEDED,
                        "target-op", "activated", NOW);
            }
            @Override public TargetOperationResult deactivate(DeploymentCommand command, Duration timeout) { return null; }
            @Override public TargetOperationResult rollback(DeploymentCommand command, Duration timeout) { return null; }
            @Override public HealthCheckResult healthCheck(DeploymentCommand command, Duration timeout) {
                return new HealthCheckResult(HealthOutcome.HEALTHY, Map.of("ready", true), "healthy", NOW);
            }
        });

        var result = withContext.activateDurably(command("context-before-dispatch"), "operator",
                "activate release", "corr-context", Duration.ofSeconds(30));

        assertTrue(result.dispatched());
        assertTrue(dispatchedWithContext.get());
        var stored = contextStore.find(result.receipt()).orElseThrow();
        assertEquals("operator", stored.actor());
        assertEquals("activate release", stored.reason());
        assertEquals("corr-context", stored.correlationId());
        assertEquals(command("context-before-dispatch"), stored.command());
    }

    @Test
    void activationReplayDoesNotCallTargetTwiceOrInventAResult() {
        var calls = new AtomicInteger();
        targets.register("target-a", target(calls));
        var first = orchestration.activateDurably(command("activate-once"), "operator",
                "activate release", "corr", Duration.ofSeconds(30));
        var replay = orchestration.activateDurably(command("activate-once"), "operator",
                "activate release", "corr", Duration.ofSeconds(30));

        assertTrue(first.dispatched());
        assertEquals(DeploymentState.ACTIVE, first.snapshot().state());
        assertEquals(DeploymentOperationStatus.SUCCEEDED, first.receipt().status());
        assertFalse(replay.dispatched());
        assertNull(replay.result());
        assertEquals(DeploymentState.ACTIVE, replay.snapshot().state());
        assertEquals(first.receipt(), replay.receipt());
        assertEquals(1, calls.get());
    }

    @Test
    void deactivationReplayDoesNotCallTargetTwice() {
        var calls = new AtomicInteger();
        targets.register("target-a", target(calls));
        var activation = orchestration.activateDurably(command("activate-first"), "operator",
                "activate release", "corr", Duration.ofSeconds(30));
        assertTrue(activation.dispatched());

        var first = orchestration.deactivateDurably(command("deactivate-once"), "operator",
                "stop release", "corr", Duration.ofSeconds(30));
        var replay = orchestration.deactivateDurably(command("deactivate-once"), "operator",
                "stop release", "corr", Duration.ofSeconds(30));

        assertTrue(first.dispatched());
        assertEquals(DeploymentState.DEACTIVATED, first.snapshot().state());
        assertFalse(replay.dispatched());
        assertNull(replay.result());
        assertEquals(2, calls.get()); // one activation and one deactivation
    }

    @Test
    void rollbackReplayDoesNotInvokeTargetTwice() {
        lifecycle.beginActivation("dep-1", 0, "operator", "initial activation", "corr", "initial-activate");
        lifecycle.recordHealth("dep-1", 1, HealthOutcome.UNHEALTHY, "operator",
                "initial activation failed", "corr", "initial-health");
        var calls = new AtomicInteger();
        targets.register("target-a", target(calls));

        var first = orchestration.rollbackDurably(command("rollback-once"), "operator",
                "restore last known good", "corr", Duration.ofSeconds(30));
        var replay = orchestration.rollbackDurably(command("rollback-once"), "operator",
                "restore last known good", "corr", Duration.ofSeconds(30));

        assertTrue(first.dispatched());
        assertEquals(DeploymentState.ROLLED_BACK, first.snapshot().state());
        assertFalse(replay.dispatched());
        assertNull(replay.result());
        assertEquals(1, calls.get());
    }

    @Test
    void ambiguousTargetOutcomeLeavesLifecycleInProgressAndReceiptUnknown() {
        targets.register("target-a", new DeploymentTarget() {
            @Override public TargetOperationResult activate(DeploymentCommand c, Duration t) {
                return new TargetOperationResult(TargetOperationResult.Status.TIMED_OUT, "remote-1",
                        "deadline elapsed", NOW);
            }
            @Override public TargetOperationResult deactivate(DeploymentCommand c, Duration t) { return null; }
            @Override public TargetOperationResult rollback(DeploymentCommand c, Duration t) { return null; }
            @Override public HealthCheckResult healthCheck(DeploymentCommand c, Duration t) { return null; }
        });

        assertThrows(IllegalStateException.class, () -> orchestration.activateDurably(command("activate-timeout"),
                "operator", "activate", "corr", Duration.ofSeconds(30)));
        assertEquals(DeploymentState.ACTIVATING, ledger.find("dep-1").orElseThrow().state());
        assertEquals(DeploymentOperationStatus.UNKNOWN,
                receipts.find("dep-1", "activate-timeout").orElseThrow().status());
    }

    private static DeploymentCommand command(String key) {
        return new DeploymentCommand("dep-1", "tenant-a", "prod", "target-a", "release-1", key, Map.of());
    }

    private static DeploymentTarget target(AtomicInteger calls) {
        return new DeploymentTarget() {
            @Override public TargetOperationResult activate(DeploymentCommand c, Duration t) {
                calls.incrementAndGet();
                return new TargetOperationResult(TargetOperationResult.Status.SUCCEEDED, "activate-" + c.idempotencyKey(),
                        "activated", NOW);
            }
            @Override public TargetOperationResult deactivate(DeploymentCommand c, Duration t) {
                calls.incrementAndGet();
                return new TargetOperationResult(TargetOperationResult.Status.SUCCEEDED, "deactivate-" + c.idempotencyKey(),
                        "deactivated", NOW);
            }
            @Override public TargetOperationResult rollback(DeploymentCommand c, Duration t) {
                calls.incrementAndGet();
                return new TargetOperationResult(TargetOperationResult.Status.SUCCEEDED, "rollback-" + c.idempotencyKey(),
                        "rolled back", NOW);
            }
            @Override public HealthCheckResult healthCheck(DeploymentCommand c, Duration t) {
                return new HealthCheckResult(HealthOutcome.HEALTHY, Map.of("ready", true), "healthy", NOW);
            }
        };
    }

    private static final class InMemoryContextStore implements DeploymentLifecycleReconciliationContextStore {
        private final Map<String, DeploymentLifecycleReconciliationContext> values = new HashMap<>();

        @Override public synchronized void save(DeploymentLifecycleReconciliationContext context) {
            String key = context.command().deploymentId() + "/" + context.command().idempotencyKey();
            var existing = values.putIfAbsent(key, context);
            if (existing != null && !existing.equals(context)) {
                throw new SecurityException("context identity conflict");
            }
        }

        @Override public synchronized Optional<DeploymentLifecycleReconciliationContext> find(
                DeploymentOperationReceipt receipt) {
            var context = values.get(receipt.deploymentId() + "/" + receipt.operationKey());
            if (context == null || !context.commandFingerprint().equals(receipt.commandFingerprint())
                    || !context.operation().equals(receipt.operation())) return Optional.empty();
            return Optional.of(context);
        }
    }

    private static final class ReceiptStore implements DeploymentOperationReceiptStore {
        private final Map<String, DeploymentOperationReceipt> values = new HashMap<>();

        @Override public synchronized DeploymentOperationReceipt claim(String d, String k, String f, String o,
                String targetKey, Instant now, Instant lease) {
            return claimExclusive(d, k, f, o, targetKey, now, lease).receipt();
        }

        @Override public synchronized DeploymentOperationClaim claimExclusive(String d, String k, String f, String o,
                String targetKey, Instant now, Instant lease) {
            String id = d + "/" + k;
            var current = values.get(id);
            if (current != null) {
                if (!current.commandFingerprint().equals(f) || !current.operation().equals(o)) {
                    throw new SecurityException("idempotency key mismatch");
                }
                return new DeploymentOperationClaim(current, false);
            }
            var created = new DeploymentOperationReceipt(d, k, f, o, DeploymentOperationStatus.RUNNING,
                    1, targetKey, null, null, lease, now, now);
            values.put(id, created);
            return new DeploymentOperationClaim(created, true);
        }

        @Override public synchronized DeploymentOperationReceipt complete(String d, String k, String f,
                DeploymentOperationStatus status, String code, String detail, Instant now) {
            String id = d + "/" + k;
            var current = values.get(id);
            if (current == null || current.status() != DeploymentOperationStatus.RUNNING
                    || !current.commandFingerprint().equals(f)) throw new IllegalStateException("receipt not RUNNING");
            var updated = new DeploymentOperationReceipt(d, k, f, current.operation(), status,
                    current.attempt(), current.targetOperationKey(), code, detail, null, current.createdAt(), now);
            values.put(id, updated);
            return updated;
        }

        @Override public synchronized Optional<DeploymentOperationReceipt> find(String d, String k) {
            return Optional.ofNullable(values.get(d + "/" + k));
        }

        @Override public synchronized List<DeploymentOperationReceipt> findExpiredRunning(Instant now, int limit) {
            return values.values().stream().filter(r -> r.status() == DeploymentOperationStatus.RUNNING
                    && r.leaseUntil() != null && !r.leaseUntil().isAfter(now)).limit(limit).toList();
        }

        @Override public synchronized DeploymentOperationReceipt markUnknown(String d, String k, String reason, Instant now) {
            String id = d + "/" + k;
            var current = values.get(id);
            if (current == null || current.status() != DeploymentOperationStatus.RUNNING) {
                throw new IllegalStateException("receipt not RUNNING");
            }
            var updated = new DeploymentOperationReceipt(d, k, current.commandFingerprint(), current.operation(),
                    DeploymentOperationStatus.UNKNOWN, current.attempt(), current.targetOperationKey(),
                    "OUTCOME_UNCERTAIN", reason, null, current.createdAt(), now);
            values.put(id, updated);
            return updated;
        }
    }
}
