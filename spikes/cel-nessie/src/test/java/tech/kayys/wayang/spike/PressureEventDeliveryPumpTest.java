package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import org.junit.jupiter.api.Test;

class PressureEventDeliveryPumpTest {
    private static final Instant NOW = Instant.parse("2026-01-01T00:00:00Z");
    private static final Clock CLOCK = Clock.fixed(NOW, ZoneOffset.UTC);
    private static final EvaluationPressureEvent EVENT = new EvaluationPressureEvent(
            NOW, EvaluationPressureAssessment.Level.NORMAL,
            EvaluationPressureAssessment.Level.ELEVATED,
            EvaluationPressureAssessment.Level.ELEVATED,
            List.of(EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_ELEVATED));

    @Test
    void successfulDeliveryIsAcknowledged() {
        var store = new InMemoryPressureEventStore();
        store.append(envelope("stream", 1));
        var delivered = new AtomicInteger();
        var pump = pump(store, ignored -> delivered.incrementAndGet(), 10);

        var result = pump.deliverOnce();

        assertEquals(1, delivered.get());
        assertEquals(new PressureEventDeliveryPump.BatchResult(1, 1, 0, 0, 0), result);
        assertEquals(0, store.pendingCount());
        assertEquals(1, pump.snapshot().acknowledged());
    }

    @Test
    void consumerFailureReleasesEventForLaterAttempt() {
        var store = new InMemoryPressureEventStore();
        store.append(envelope("stream", 1));
        var attempts = new AtomicInteger();
        var pump = pump(store, ignored -> {
            if (attempts.incrementAndGet() == 1) throw new IllegalStateException("temporary");
        }, 10);

        var first = pump.deliverOnce();
        assertEquals(new PressureEventDeliveryPump.BatchResult(1, 0, 1, 0, 0), first);
        assertEquals(1, store.pendingCount());

        var second = pump.deliverOnce();
        assertEquals(new PressureEventDeliveryPump.BatchResult(1, 1, 0, 0, 0), second);
        assertEquals(2, attempts.get());
        assertEquals(1, store.acknowledgedCount());
    }

    @Test
    void eachPassIsBoundedByConfiguredBatchSize() {
        var store = new InMemoryPressureEventStore();
        store.append(envelope("stream", 1));
        store.append(envelope("stream", 2));
        store.append(envelope("stream", 3));
        var delivered = new AtomicInteger();
        var pump = pump(store, ignored -> delivered.incrementAndGet(), 2);

        assertEquals(2, pump.deliverOnce().claimed());
        assertEquals(1, store.pendingCount());
        assertEquals(1, pump.deliverOnce().claimed());
        assertEquals(3, delivered.get());
    }

    @Test
    void rejectsInvalidConfiguration() {
        var store = new InMemoryPressureEventStore();
        assertThrows(IllegalArgumentException.class,
                () -> pump(store, ignored -> { }, 0));
        assertThrows(IllegalArgumentException.class,
                () -> new PressureEventDeliveryPump(store, ignored -> { }, "worker",
                        1, Duration.ZERO, CLOCK));
        assertThrows(IllegalArgumentException.class,
                () -> new PressureEventDeliveryPump(store, ignored -> { }, " ",
                        1, Duration.ofSeconds(1), CLOCK));
    }

    @Test
    void emptyStoreProducesEmptyBatch() {
        var pump = pump(new InMemoryPressureEventStore(), ignored -> {
            throw new AssertionError("must not be invoked");
        }, 5);
        assertEquals(0, pump.deliverOnce().claimed());
        assertTrue(pump.snapshot().claimed() == 0);
    }

    private static PressureEventDeliveryPump pump(
            PressureEventStore store, PressureEventConsumer consumer, int batchSize) {
        return new PressureEventDeliveryPump(store, consumer, "pressure-events-test",
                batchSize, Duration.ofMinutes(1), CLOCK);
    }

    private static PressureEventEnvelope envelope(String stream, long sequence) {
        return new PressureEventEnvelope(UUID.randomUUID(), stream, sequence, NOW, EVENT);
    }
}
