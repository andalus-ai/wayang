package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;

class BoundedPressureEventDispatcherTest {
    private static EvaluationPressureEvent event(int index) {
        var levels = EvaluationPressureAssessment.Level.values();
        var previous = levels[index % levels.length];
        var current = levels[(index + 1) % levels.length];
        return new EvaluationPressureEvent(Instant.ofEpochSecond(index), previous, current, current, List.of());
    }

    @Test
    void drainsAcceptedEventsOnCloseAndRejectsLaterEvents() throws Exception {
        var received = new CopyOnWriteArrayList<EvaluationPressureEvent>();
        var dispatcher = new BoundedPressureEventDispatcher(4,
                BoundedPressureEventDispatcher.OverflowPolicy.REJECT_NEWEST, received::add);
        dispatcher.onPressureTransition(event(0));
        dispatcher.onPressureTransition(event(1));
        dispatcher.close();
        dispatcher.onPressureTransition(event(2));
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(3)));
        assertEquals(2, received.size());
        var snapshot = dispatcher.snapshot();
        assertEquals(2, snapshot.accepted());
        assertEquals(2, snapshot.delivered());
        assertEquals(1, snapshot.rejected());
        assertTrue(snapshot.closed());
        assertTrue(snapshot.terminated());
    }

    @Test
    void rejectNewestPolicyBoundsQueue() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var dispatcher = new BoundedPressureEventDispatcher(1,
                BoundedPressureEventDispatcher.OverflowPolicy.REJECT_NEWEST, e -> {
                    entered.countDown();
                    try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                });
        dispatcher.onPressureTransition(event(0));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        dispatcher.onPressureTransition(event(1));
        dispatcher.onPressureTransition(event(2));
        assertEquals(1, dispatcher.snapshot().queued());
        assertEquals(1, dispatcher.snapshot().rejected());
        release.countDown();
        dispatcher.close();
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(3)));
        assertEquals(2, dispatcher.snapshot().delivered());
    }

    @Test
    void dropOldestPolicyCountsDroppedEvent() throws Exception {
        var entered = new CountDownLatch(1);
        var release = new CountDownLatch(1);
        var received = new CopyOnWriteArrayList<EvaluationPressureEvent>();
        var dispatcher = new BoundedPressureEventDispatcher(1,
                BoundedPressureEventDispatcher.OverflowPolicy.DROP_OLDEST, e -> {
                    if (e.occurredAt().getEpochSecond() == 0) {
                        entered.countDown();
                        try { release.await(); } catch (InterruptedException ex) { Thread.currentThread().interrupt(); }
                    }
                    received.add(e);
                });
        dispatcher.onPressureTransition(event(0));
        assertTrue(entered.await(2, TimeUnit.SECONDS));
        dispatcher.onPressureTransition(event(1));
        dispatcher.onPressureTransition(event(2));
        assertEquals(1, dispatcher.snapshot().dropped());
        release.countDown();
        dispatcher.close();
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(3)));
        assertEquals(List.of(0L, 2L), received.stream().map(e -> e.occurredAt().getEpochSecond()).toList());
    }

    @Test
    void delegateFailureDoesNotStopDispatcher() throws Exception {
        var calls = new java.util.concurrent.atomic.AtomicInteger();
        var dispatcher = new BoundedPressureEventDispatcher(2,
                BoundedPressureEventDispatcher.OverflowPolicy.REJECT_NEWEST, e -> {
                    if (calls.getAndIncrement() == 0) throw new IllegalStateException("listener");
                });
        dispatcher.onPressureTransition(event(0));
        dispatcher.onPressureTransition(event(1));
        dispatcher.close();
        assertTrue(dispatcher.awaitTermination(Duration.ofSeconds(3)));
        assertEquals(1, dispatcher.snapshot().listenerFailures());
        assertEquals(1, dispatcher.snapshot().delivered());
    }

    @Test
    void validatesCapacityAndTimeout() {
        assertThrows(IllegalArgumentException.class, () -> new BoundedPressureEventDispatcher(0,
                BoundedPressureEventDispatcher.OverflowPolicy.REJECT_NEWEST, e -> {}));
        var dispatcher = new BoundedPressureEventDispatcher(1,
                BoundedPressureEventDispatcher.OverflowPolicy.REJECT_NEWEST, e -> {});
        assertThrows(IllegalArgumentException.class, () -> dispatcher.awaitTermination(Duration.ofSeconds(-1)));
        dispatcher.close();
    }
}
