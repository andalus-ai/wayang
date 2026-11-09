package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import org.junit.jupiter.api.Test;

class PressureEventAdapterGroupTest {
    private static final EvaluationPressureEvent EVENT = new EvaluationPressureEvent(
            Instant.parse("2026-01-01T00:00:00Z"),
            EvaluationPressureAssessment.Level.NORMAL,
            EvaluationPressureAssessment.Level.ELEVATED,
            EvaluationPressureAssessment.Level.ELEVATED,
            List.of(EvaluationPressureAssessment.Reason.ADMISSION_ACTIVE_ELEVATED));

    @Test
    void fansOutToEveryAdapterAndIsolatesDeliveryFailure() {
        var delivered = new ArrayList<String>();
        var first = adapter("first", delivered, true, false);
        var second = adapter("second", delivered, false, false);
        var group = new PressureEventAdapterGroup(List.of(first, second));

        group.onPressureTransition(EVENT);

        assertEquals(List.of("first", "second"), delivered);
        assertEquals(1, group.snapshot().deliveryFailures());
        assertEquals(2, group.snapshot().adapterCount());
        group.close();
    }

    @Test
    void closesInReverseOrderAndAttemptsEveryAdapterWhenOneCloseFails() {
        var closed = new ArrayList<String>();
        var group = new PressureEventAdapterGroup(List.of(
                adapter("first", new ArrayList<>(), false, false, closed),
                adapter("second", new ArrayList<>(), false, true, closed),
                adapter("third", new ArrayList<>(), false, false, closed)));

        var failure = assertThrows(IllegalStateException.class, group::close);

        assertEquals(List.of("third", "second", "first"), closed);
        assertEquals(1, failure.getSuppressed().length);
        assertTrue(group.snapshot().closed());
        group.close();
        assertEquals(3, closed.size());
    }

    @Test
    void closeWaitsForInFlightDispatchAndRejectsLaterEvents() throws Exception {
        var entered = new java.util.concurrent.CountDownLatch(1);
        var release = new java.util.concurrent.CountDownLatch(1);
        var delivered = new ArrayList<String>();
        var adapter = new PressureEventAdapter() {
            @Override public String providerId() { return "test.blocking"; }
            @Override public String providerVersion() { return "1"; }
            @Override public void onPressureTransition(EvaluationPressureEvent event) {
                entered.countDown();
                try {
                    release.await();
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    throw new IllegalStateException(e);
                }
                delivered.add("event");
            }
            @Override public void close() { delivered.add("closed"); }
        };
        var group = new PressureEventAdapterGroup(List.of(adapter));
        var dispatch = new Thread(() -> group.onPressureTransition(EVENT));
        dispatch.start();
        assertTrue(entered.await(2, java.util.concurrent.TimeUnit.SECONDS));

        var close = new Thread(group::close);
        close.start();
        release.countDown();
        dispatch.join(2_000);
        close.join(2_000);

        assertTrue(!dispatch.isAlive());
        assertTrue(!close.isAlive());
        assertEquals(List.of("event", "closed"), delivered);
        group.onPressureTransition(EVENT);
        assertEquals(2, delivered.size());
    }

    private static PressureEventAdapter adapter(
            String id, List<String> delivered, boolean failDelivery, boolean failClose) {
        return adapter(id, delivered, failDelivery, failClose, new ArrayList<>());
    }

    private static PressureEventAdapter adapter(
            String id, List<String> delivered, boolean failDelivery, boolean failClose, List<String> closed) {
        return new PressureEventAdapter() {
            @Override public String providerId() { return id; }
            @Override public String providerVersion() { return "1"; }
            @Override public void onPressureTransition(EvaluationPressureEvent event) {
                delivered.add(id);
                if (failDelivery) throw new IllegalStateException("test-only");
            }
            @Override public void close() {
                closed.add(id);
                if (failClose) throw new IllegalStateException("test-only");
            }
        };
    }
}
