package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

/**
 * Reusable behavioral contract for PressureEventStore implementations.
 * Durable adapters should extend this class and provide an isolated store.
 */
abstract class PressureEventStoreContract {
    protected abstract PressureEventStore newStore();

    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final EvaluationPressureEvent EVENT = new EvaluationPressureEvent(
            NOW, EvaluationPressureAssessment.Level.NORMAL,
            EvaluationPressureAssessment.Level.ELEVATED,
            EvaluationPressureAssessment.Level.ELEVATED,
            List.of(EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_ELEVATED));

    @Test
    void appendMustBeIdempotentForTheSameEnvelope() {
        var store = newStore();
        var envelope = event("contract-stream", 1);
        assertEquals(PressureEventAppendResult.APPENDED, store.append(envelope));
        assertEquals(PressureEventAppendResult.DUPLICATE, store.append(envelope));
    }

    @Test
    void appendMustRejectEventIdOrStreamSequenceReuseWithDifferentContent() {
        var store = newStore();
        var first = event("contract-stream", 1);
        store.append(first);
        var conflictingId = new PressureEventEnvelope(first.eventId(), "contract-stream", 2,
                NOW, EVENT);
        var conflictingSequence = event("contract-stream", 1);
        assertThrows(IllegalStateException.class, () -> store.append(conflictingId));
        assertThrows(IllegalStateException.class, () -> store.append(conflictingSequence));
    }

    @Test
    void concurrentAppendOfOneEnvelopeMustProduceOneAppendAndDuplicates() throws Exception {
        var store = newStore();
        var envelope = event("concurrent-append", 1);
        int writers = 16;
        var ready = new CountDownLatch(writers);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(writers)) {
            var futures = new ArrayList<java.util.concurrent.Future<PressureEventAppendResult>>();
            for (int i = 0; i < writers; i++) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
                    return store.append(envelope);
                }));
            }
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            long appended = 0;
            long duplicate = 0;
            for (var future : futures) {
                switch (future.get(5, TimeUnit.SECONDS)) {
                    case APPENDED -> appended++;
                    case DUPLICATE -> duplicate++;
                }
            }
            assertEquals(1, appended);
            assertEquals(writers - 1, duplicate);
        }
    }

    @Test
    void concurrentClaimsMustNotReturnTheSameEventTwice() throws Exception {
        var store = newStore();
        int events = 24;
        for (int i = 1; i <= events; i++) store.append(event("claim-race", i));
        var ready = new CountDownLatch(2);
        var start = new CountDownLatch(1);
        try (var pool = Executors.newFixedThreadPool(2)) {
            var first = pool.submit(() -> claimAfterBarrier(store, "worker-one", ready, start));
            var second = pool.submit(() -> claimAfterBarrier(store, "worker-two", ready, start));
            assertTrue(ready.await(5, TimeUnit.SECONDS));
            start.countDown();
            var combined = new ArrayList<ClaimedPressureEvent>();
            combined.addAll(first.get(5, TimeUnit.SECONDS));
            combined.addAll(second.get(5, TimeUnit.SECONDS));
            var ids = new HashSet<UUID>();
            for (var claim : combined) assertTrue(ids.add(claim.envelope().eventId()));
            assertEquals(events, combined.size());
        }
    }

    @Test
    void expiredClaimMustBeReissuedWithAFreshFencingToken() {
        var store = newStore();
        var envelope = event("lease-contract", 1);
        store.append(envelope);
        var first = store.claim("worker-a", 1, NOW, Duration.ofSeconds(5)).getFirst();
        assertTrue(store.acknowledge("worker-a", envelope.eventId(),
                first.claimToken(), NOW.plusSeconds(5)) == false);
        var second = store.claim("worker-b", 1, NOW.plusSeconds(5), Duration.ofSeconds(5)).getFirst();
        assertFalse(first.claimToken().equals(second.claimToken()));
        assertFalse(store.acknowledge("worker-a", envelope.eventId(), first.claimToken(), NOW.plusSeconds(6)));
        assertTrue(store.acknowledge("worker-b", envelope.eventId(), second.claimToken(), NOW.plusSeconds(6)));
    }

    @Test
    void releaseMustMakeCurrentClaimAvailableAgain() {
        var store = newStore();
        var envelope = event("release-contract", 1);
        store.append(envelope);
        var first = store.claim("worker-a", 1, NOW, Duration.ofMinutes(1)).getFirst();
        assertTrue(store.release("worker-a", envelope.eventId(), first.claimToken()));
        assertFalse(store.release("worker-a", envelope.eventId(), first.claimToken()));
        var second = store.claim("worker-b", 1, NOW, Duration.ofMinutes(1)).getFirst();
        assertEquals(2, second.deliveryAttempts());
    }

    private static List<ClaimedPressureEvent> claimAfterBarrier(
            PressureEventStore store, String worker, CountDownLatch ready, CountDownLatch start)
            throws InterruptedException {
        ready.countDown();
        if (!start.await(5, TimeUnit.SECONDS)) throw new IllegalStateException("start timed out");
        return store.claim(worker, 24, NOW, Duration.ofMinutes(1));
    }

    private static PressureEventEnvelope event(String stream, long sequence) {
        return new PressureEventEnvelope(UUID.randomUUID(), stream, sequence, NOW, EVENT);
    }
}
