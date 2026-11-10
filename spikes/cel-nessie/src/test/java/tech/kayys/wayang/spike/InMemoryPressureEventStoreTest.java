package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class InMemoryPressureEventStoreTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final EvaluationPressureEvent EVENT = new EvaluationPressureEvent(
            NOW, EvaluationPressureAssessment.Level.NORMAL,
            EvaluationPressureAssessment.Level.ELEVATED,
            EvaluationPressureAssessment.Level.ELEVATED,
            List.of(EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_ELEVATED));

    @Test
    void appendIsIdempotentAndRejectsConflictingStreamSequence() {
        var store = new InMemoryPressureEventStore();
        var first = envelope(UUID.randomUUID(), "runtime-a", 1);
        assertEquals(PressureEventAppendResult.APPENDED, store.append(first));
        assertEquals(PressureEventAppendResult.DUPLICATE, store.append(first));
        assertThrows(IllegalStateException.class,
                () -> store.append(envelope(UUID.randomUUID(), "runtime-a", 1)));
        assertEquals(1, store.pendingCount());
    }

    @Test
    void claimLeaseExpiresAndFencesStaleAcknowledgement() {
        var store = new InMemoryPressureEventStore();
        var event = envelope(UUID.randomUUID(), "runtime-a", 1);
        store.append(event);

        var first = store.claim("worker-a", 10, NOW, Duration.ofSeconds(5)).getFirst();
        assertFalse(store.acknowledge("worker-a", event.eventId(), first.claimToken(), NOW.plusSeconds(5)));

        var second = store.claim("worker-b", 10, NOW.plusSeconds(5), Duration.ofSeconds(5)).getFirst();
        assertFalse(store.acknowledge("worker-a", event.eventId(), first.claimToken(), NOW.plusSeconds(5)));
        assertTrue(store.acknowledge("worker-b", event.eventId(), second.claimToken(), NOW.plusSeconds(6)));
        assertEquals(0, store.pendingCount());
        assertEquals(1, store.acknowledgedCount());
    }

    @Test
    void releaseMakesEventAvailableAndIncrementsAttemptCount() {
        var store = new InMemoryPressureEventStore();
        var event = envelope(UUID.randomUUID(), "runtime-a", 3);
        store.append(event);

        var first = store.claim("worker-a", 1, NOW, Duration.ofMinutes(1)).getFirst();
        assertTrue(store.release("worker-a", event.eventId(), first.claimToken()));
        assertFalse(store.release("worker-a", event.eventId(), first.claimToken()));

        var second = store.claim("worker-b", 1, NOW, Duration.ofMinutes(1)).getFirst();
        assertEquals(2, second.deliveryAttempts());
    }

    @Test
    void claimIsBoundedAndOrderedByStreamThenSequence() {
        var store = new InMemoryPressureEventStore();
        store.append(envelope(UUID.randomUUID(), "z-stream", 1));
        store.append(envelope(UUID.randomUUID(), "a-stream", 2));
        store.append(envelope(UUID.randomUUID(), "a-stream", 1));

        var claimed = store.claim("worker", 2, NOW, Duration.ofMinutes(1));
        assertEquals(2, claimed.size());
        assertEquals(List.of(1L, 2L), claimed.stream().map(c -> c.envelope().sequence()).toList());
        assertEquals("a-stream", claimed.getFirst().envelope().streamId());
    }

    @Test
    void staleConsumerCannotAcknowledgeAnotherConsumersClaim() {
        var store = new InMemoryPressureEventStore();
        var event = envelope(UUID.randomUUID(), "runtime-a", 1);
        store.append(event);
        var claim = store.claim("worker-a", 1, NOW, Duration.ofMinutes(1)).getFirst();

        assertFalse(store.acknowledge("worker-b", event.eventId(), claim.claimToken(), NOW));
        assertEquals(1, store.pendingCount());
    }

    private static PressureEventEnvelope envelope(UUID id, String stream, long sequence) {
        return new PressureEventEnvelope(id, stream, sequence, NOW, EVENT);
    }
}
