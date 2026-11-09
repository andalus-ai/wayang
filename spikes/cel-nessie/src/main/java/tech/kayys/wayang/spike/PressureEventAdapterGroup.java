package tech.kayys.wayang.spike;

import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;

/**
 * Host-owned fan-out and lifecycle boundary for multiple pressure-event adapters.
 *
 * <p>Dispatch is best-effort: a failing adapter does not prevent delivery to
 * later adapters. Closing the group stops new dispatches, waits for in-flight
 * callbacks to finish, and closes adapters in reverse construction order.</p>
 */
public final class PressureEventAdapterGroup implements EvaluationPressureEventListener, AutoCloseable {
    private final List<PressureEventAdapter> adapters;
    private final LongAdder deliveryFailures = new LongAdder();
    private final ReentrantLock lifecycleLock = new ReentrantLock();
    private final Condition dispatchesDrained = lifecycleLock.newCondition();
    private boolean closed;
    private int activeDispatches;

    public PressureEventAdapterGroup(Collection<? extends PressureEventAdapter> adapters) {
        Objects.requireNonNull(adapters, "adapters");
        var copy = new ArrayList<PressureEventAdapter>(adapters.size());
        for (var adapter : adapters) {
            copy.add(Objects.requireNonNull(adapter, "adapter"));
        }
        this.adapters = List.copyOf(copy);
    }

    @Override
    public void onPressureTransition(EvaluationPressureEvent event) {
        Objects.requireNonNull(event, "event");
        lifecycleLock.lock();
        try {
            if (closed) {
                return;
            }
            activeDispatches++;
        } finally {
            lifecycleLock.unlock();
        }

        try {
            for (var adapter : adapters) {
                try {
                    adapter.onPressureTransition(event);
                } catch (RuntimeException failure) {
                    deliveryFailures.increment();
                }
            }
        } finally {
            lifecycleLock.lock();
            try {
                activeDispatches--;
                if (activeDispatches == 0) {
                    dispatchesDrained.signalAll();
                }
            } finally {
                lifecycleLock.unlock();
            }
        }
    }

    /** Immutable aggregate lifecycle snapshot; contains no tenant or event data. */
    public Snapshot snapshot() {
        lifecycleLock.lock();
        try {
            return new Snapshot(adapters.size(), deliveryFailures.sum(), activeDispatches, closed);
        } finally {
            lifecycleLock.unlock();
        }
    }

    @Override
    public void close() {
        lifecycleLock.lock();
        try {
            if (closed) {
                return;
            }
            closed = true;
            boolean interrupted = false;
            while (activeDispatches != 0) {
                try {
                    dispatchesDrained.await();
                } catch (InterruptedException ignored) {
                    interrupted = true;
                }
            }
            if (interrupted) {
                Thread.currentThread().interrupt();
            }
        } finally {
            lifecycleLock.unlock();
        }

        RuntimeException aggregate = null;
        for (int i = adapters.size() - 1; i >= 0; i--) {
            try {
                adapters.get(i).close();
            } catch (RuntimeException failure) {
                if (aggregate == null) {
                    aggregate = new IllegalStateException("One or more pressure-event adapters failed to close");
                }
                aggregate.addSuppressed(failure);
            }
        }
        if (aggregate != null) {
            throw aggregate;
        }
    }

    public record Snapshot(int adapterCount, long deliveryFailures, int activeDispatches, boolean closed) {
        public Snapshot {
            if (adapterCount < 0 || deliveryFailures < 0 || activeDispatches < 0) {
                throw new IllegalArgumentException("snapshot values must be non-negative");
            }
        }
    }
}
