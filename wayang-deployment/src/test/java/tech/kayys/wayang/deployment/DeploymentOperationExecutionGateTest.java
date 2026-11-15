package tech.kayys.wayang.deployment;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class DeploymentOperationExecutionGateTest {
    private static final Instant NOW = Instant.parse("2026-11-15T00:00:00Z");

    @Test
    void onlyTheExclusiveClaimWinnerRunsTheSideEffect() {
        var store = new FakeStore();
        var receipts = new DeploymentOperationReceiptService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        var gate = new DeploymentOperationExecutionGate(receipts);
        var calls = new AtomicInteger();

        var first = gate.execute("ACTIVATE", command("activate-1"), Duration.ofMinutes(2),
                () -> "activated", ignored -> DeploymentOperationStatus.SUCCEEDED,
                ignored -> "TARGET_ACTIVE", ignored -> "target acknowledged");
        var duplicate = gate.execute("ACTIVATE", command("activate-1"), Duration.ofMinutes(2),
                () -> { calls.incrementAndGet(); return "must-not-run"; },
                ignored -> DeploymentOperationStatus.SUCCEEDED, ignored -> "BAD", ignored -> "bad");

        assertTrue(first.dispatched());
        assertEquals("activated", first.result());
        assertEquals(DeploymentOperationStatus.SUCCEEDED, first.receipt().status());
        assertFalse(duplicate.dispatched());
        assertNull(duplicate.result());
        assertEquals(DeploymentOperationStatus.SUCCEEDED, duplicate.receipt().status());
        assertEquals(0, calls.get());
    }

    @Test
    void ambiguousDispatchExceptionMarksReceiptUnknownAndPropagates() {
        var store = new FakeStore();
        var receipts = new DeploymentOperationReceiptService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        var gate = new DeploymentOperationExecutionGate(receipts);

        assertThrows(IllegalStateException.class, () -> gate.execute("ACTIVATE", command("activate-2"),
                Duration.ofMinutes(2), () -> { throw new IllegalStateException("adapter disconnected"); },
                ignored -> DeploymentOperationStatus.SUCCEEDED, ignored -> "OK", ignored -> "ok"));

        assertEquals(DeploymentOperationStatus.UNKNOWN,
                store.find("dep-1", "activate-2").orElseThrow().status());
    }

    @Test
    void invalidOutcomeClassificationFailsClosedAsUnknown() {
        var store = new FakeStore();
        var receipts = new DeploymentOperationReceiptService(store, Clock.fixed(NOW, ZoneOffset.UTC));
        var gate = new DeploymentOperationExecutionGate(receipts);

        assertThrows(IllegalArgumentException.class, () -> gate.execute("ACTIVATE", command("activate-3"),
                Duration.ofMinutes(2), () -> "accepted", ignored -> DeploymentOperationStatus.UNKNOWN,
                ignored -> "UNKNOWN", ignored -> "not terminal"));

        assertEquals(DeploymentOperationStatus.UNKNOWN,
                store.find("dep-1", "activate-3").orElseThrow().status());
    }

    private static DeploymentCommand command(String key) {
        return new DeploymentCommand("dep-1", "tenant-a", "prod", "target-a", "release-1", key, java.util.Map.of());
    }

    private static final class FakeStore implements DeploymentOperationReceiptStore {
        private final java.util.Map<String, DeploymentOperationReceipt> data = new java.util.HashMap<>();

        @Override public synchronized DeploymentOperationReceipt claim(String d, String k, String f, String o,
                String t, Instant now, Instant lease) {
            return claimExclusive(d, k, f, o, t, now, lease).receipt();
        }

        @Override public synchronized DeploymentOperationClaim claimExclusive(String d, String k, String f, String o,
                String t, Instant now, Instant lease) {
            String id = d + "/" + k;
            var existing = data.get(id);
            if (existing != null) {
                if (!existing.commandFingerprint().equals(f) || !existing.operation().equals(o)) {
                    throw new SecurityException("idempotency mismatch");
                }
                return new DeploymentOperationClaim(existing, false);
            }
            var created = new DeploymentOperationReceipt(d, k, f, o, DeploymentOperationStatus.RUNNING,
                    1, t, null, null, lease, now, now);
            data.put(id, created);
            return new DeploymentOperationClaim(created, true);
        }

        @Override public synchronized DeploymentOperationReceipt complete(String d, String k, String f,
                DeploymentOperationStatus status, String code, String detail, Instant now) {
            String id = d + "/" + k;
            var current = data.get(id);
            if (current == null || current.status() != DeploymentOperationStatus.RUNNING
                    || !current.commandFingerprint().equals(f)) throw new IllegalStateException("receipt changed");
            var completed = new DeploymentOperationReceipt(d, k, f, current.operation(), status,
                    current.attempt(), current.targetOperationKey(), code, detail, null, current.createdAt(), now);
            data.put(id, completed);
            return completed;
        }

        @Override public synchronized Optional<DeploymentOperationReceipt> find(String d, String k) {
            return Optional.ofNullable(data.get(d + "/" + k));
        }

        @Override public synchronized List<DeploymentOperationReceipt> findExpiredRunning(Instant now, int limit) {
            return data.values().stream().filter(r -> r.status() == DeploymentOperationStatus.RUNNING
                    && r.leaseUntil() != null && !r.leaseUntil().isAfter(now)).limit(limit).toList();
        }

        @Override public synchronized DeploymentOperationReceipt markUnknown(String d, String k, String reason, Instant now) {
            String id = d + "/" + k;
            var current = data.get(id);
            if (current == null || current.status() != DeploymentOperationStatus.RUNNING) {
                throw new IllegalStateException("receipt changed");
            }
            var unknown = new DeploymentOperationReceipt(d, k, current.commandFingerprint(), current.operation(),
                    DeploymentOperationStatus.UNKNOWN, current.attempt(), current.targetOperationKey(),
                    "OUTCOME_UNCERTAIN", reason, null, current.createdAt(), now);
            data.put(id, unknown);
            return unknown;
        }
    }
}
