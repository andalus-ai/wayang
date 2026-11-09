package tech.kayys.wayang.spike;

import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.LongAdder;

/**
 * Optional bounded, single-consumer dispatcher for pressure transition events.
 * Delivery is process-local and best-effort; close() stops acceptance and drains
 * events already accepted into the queue.
 */
public final class BoundedPressureEventDispatcher implements EvaluationPressureEventListener, AutoCloseable {
    public enum OverflowPolicy { REJECT_NEWEST, DROP_OLDEST }

    private final Object lifecycleLock = new Object();
    private final ArrayBlockingQueue<EvaluationPressureEvent> queue;
    private final EvaluationPressureEventListener delegate;
    private final OverflowPolicy overflowPolicy;
    private final CountDownLatch terminated = new CountDownLatch(1);
    private final LongAdder accepted = new LongAdder();
    private final LongAdder delivered = new LongAdder();
    private final LongAdder dropped = new LongAdder();
    private final LongAdder rejected = new LongAdder();
    private final LongAdder listenerFailures = new LongAdder();
    private volatile boolean accepting = true;
    private final Thread worker;

    public BoundedPressureEventDispatcher(int capacity, OverflowPolicy overflowPolicy,
            EvaluationPressureEventListener delegate) {
        if (capacity < 1) throw new IllegalArgumentException("capacity must be at least one");
        this.queue = new ArrayBlockingQueue<>(capacity);
        this.overflowPolicy = Objects.requireNonNull(overflowPolicy, "overflowPolicy");
        this.delegate = Objects.requireNonNull(delegate, "delegate");
        this.worker = new Thread(this::dispatchLoop, "wayang-pressure-event-dispatcher");
        this.worker.setDaemon(true);
        this.worker.start();
    }

    @Override
    public void onPressureTransition(EvaluationPressureEvent event) {
        Objects.requireNonNull(event, "event");
        synchronized (lifecycleLock) {
            if (!accepting) {
                rejected.increment();
                return;
            }
            if (queue.offer(event)) {
                accepted.increment();
                return;
            }
            if (overflowPolicy == OverflowPolicy.REJECT_NEWEST) {
                rejected.increment();
                return;
            }
            queue.poll();
            dropped.increment();
            if (queue.offer(event)) accepted.increment();
            else rejected.increment();
        }
    }

    private void dispatchLoop() {
        try {
            while (accepting || !queue.isEmpty()) {
                EvaluationPressureEvent event;
                try {
                    event = queue.poll(100, TimeUnit.MILLISECONDS);
                } catch (InterruptedException interrupted) {
                    // Interruption is a shutdown signal; accepted events are still drained.
                    continue;
                }
                if (event == null) continue;
                try {
                    delegate.onPressureTransition(event);
                    delivered.increment();
                } catch (RuntimeException failure) {
                    listenerFailures.increment();
                }
            }
        } finally {
            terminated.countDown();
        }
    }

    /** Stop accepting new events; the worker drains all events already accepted. */
    @Override
    public void close() {
        synchronized (lifecycleLock) {
            accepting = false;
        }
    }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        return terminated.await(timeout.toNanos(), TimeUnit.NANOSECONDS);
    }

    public boolean isTerminated() { return terminated.getCount() == 0; }

    public Snapshot snapshot() {
        return new Snapshot(queue.size(), accepted.sum(), delivered.sum(), dropped.sum(),
                rejected.sum(), listenerFailures.sum(), !accepting, isTerminated());
    }

    public record Snapshot(int queued, long accepted, long delivered, long dropped,
            long rejected, long listenerFailures, boolean closed, boolean terminated) {
        public Snapshot {
            if (queued < 0 || accepted < 0 || delivered < 0 || dropped < 0
                    || rejected < 0 || listenerFailures < 0) {
                throw new IllegalArgumentException("snapshot values must not be negative");
            }
        }
    }
}
