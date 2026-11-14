package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class DeploymentOperationRecoveryServiceTest {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");

    @Test
    void persistsConfirmedTargetSuccessWithoutRedispatch() {
        var store = new FakeStore(receipt());
        var ledger = ledger();
        var registry = new DeploymentTargetRegistry();
        var target = target(DeploymentOperationObservation.Status.SUCCEEDED);
        registry.register("target-a", target);
        var service = new DeploymentOperationRecoveryService(ledger, store, registry,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var report = service.reconcileExpired(Duration.ofSeconds(10), 10);

        assertEquals(1, report.scanned());
        assertEquals(1, report.succeeded());
        assertEquals(DeploymentOperationStatus.SUCCEEDED, store.current.status());
    }

    @Test
    void leavesAnObservedInProgressOperationRunning() {
        var store = new FakeStore(receipt());
        var registry = new DeploymentTargetRegistry();
        registry.register("target-a", target(DeploymentOperationObservation.Status.IN_PROGRESS));
        var service = new DeploymentOperationRecoveryService(ledger(), store, registry,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var report = service.reconcileExpired(Duration.ofSeconds(10), 10);

        assertEquals(1, report.stillRunning());
        assertEquals(DeploymentOperationStatus.RUNNING, store.current.status());
    }

    @Test
    void marksUnobservableOperationUnknownAndNeverRetriesIt() {
        var store = new FakeStore(receipt());
        var registry = new DeploymentTargetRegistry();
        // The default observation contract is fail-closed.
        registry.register("target-a", target(DeploymentOperationObservation.Status.UNKNOWN));
        var service = new DeploymentOperationRecoveryService(ledger(), store, registry,
                Clock.fixed(NOW, ZoneOffset.UTC));

        var report = service.reconcileExpired(Duration.ofSeconds(10), 10);

        assertEquals(1, report.unknown());
        assertEquals(DeploymentOperationStatus.UNKNOWN, store.current.status());
    }

    @Test
    void rejectsUnboundedRecoveryRequests() {
        var service = new DeploymentOperationRecoveryService(ledger(), new FakeStore(receipt()),
                new DeploymentTargetRegistry(), Clock.fixed(NOW, ZoneOffset.UTC));
        assertThrows(IllegalArgumentException.class,
                () -> service.reconcileExpired(Duration.ofMinutes(11), 10));
        assertThrows(IllegalArgumentException.class,
                () -> service.reconcileExpired(Duration.ofSeconds(1), 1001));
    }

    private static InMemoryDeploymentLedger ledger() {
        var ledger = new InMemoryDeploymentLedger();
        var lifecycle = new DeploymentLifecycleService(ledger, Clock.fixed(NOW, ZoneOffset.UTC));
        lifecycle.registerInstalled("dep-1", "tenant-a", "prod", "target-a", "release-1",
                "test", "installed", "corr-install", "install-1");
        return ledger;
    }

    private static DeploymentOperationReceipt receipt() {
        return new DeploymentOperationReceipt("dep-1", "op-1", "fingerprint-1", "activate",
                DeploymentOperationStatus.RUNNING, 1, "target-op-1", null, null,
                NOW.minusSeconds(1), NOW.minusSeconds(60), NOW.minusSeconds(1));
    }

    private static DeploymentTarget target(DeploymentOperationObservation.Status status) {
        return new DeploymentTarget() {
            @Override public TargetOperationResult activate(DeploymentCommand c, Duration t) { return null; }
            @Override public TargetOperationResult deactivate(DeploymentCommand c, Duration t) { return null; }
            @Override public TargetOperationResult rollback(DeploymentCommand c, Duration t) { return null; }
            @Override public HealthCheckResult healthCheck(DeploymentCommand c, Duration t) { return null; }
            @Override public DeploymentOperationObservation observeOperation(DeploymentOperationReceipt receipt, Duration timeout) {
                return new DeploymentOperationObservation(status, "observed by test", NOW);
            }
        };
    }

    private static final class FakeStore implements DeploymentOperationReceiptStore {
        private DeploymentOperationReceipt current;
        private FakeStore(DeploymentOperationReceipt receipt) { current = receipt; }
        @Override public DeploymentOperationReceipt claim(String d, String k, String f, String o, String t, Instant n, Instant l) {
            throw new UnsupportedOperationException();
        }
        @Override public DeploymentOperationReceipt complete(String d, String k, String f, DeploymentOperationStatus status,
                String code, String detail, Instant now) {
            if (current.status() != DeploymentOperationStatus.RUNNING || !current.commandFingerprint().equals(f)) {
                throw new IllegalStateException("receipt changed");
            }
            current = new DeploymentOperationReceipt(d, k, f, current.operation(), status, current.attempt(),
                    current.targetOperationKey(), code, detail, null, current.createdAt(), now);
            return current;
        }
        @Override public java.util.Optional<DeploymentOperationReceipt> find(String d, String k) {
            return java.util.Optional.ofNullable(current);
        }
        @Override public List<DeploymentOperationReceipt> findExpiredRunning(Instant now, int limit) {
            if (current != null && current.status() == DeploymentOperationStatus.RUNNING
                    && current.leaseUntil() != null && !current.leaseUntil().isAfter(now)) return List.of(current);
            return List.of();
        }
        @Override public DeploymentOperationReceipt markUnknown(String d, String k, String reason, Instant now) {
            return complete(d, k, current.commandFingerprint(), DeploymentOperationStatus.UNKNOWN,
                    "OUTCOME_UNCERTAIN", reason, now);
        }
    }
}
