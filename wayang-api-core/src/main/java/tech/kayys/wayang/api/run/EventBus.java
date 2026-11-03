package tech.kayys.wayang.api.run;

import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.function.Consumer;

/**
 * In-process publish/subscribe for {@link RunEvent}s. Listeners run on the
 * publisher's thread and <strong>must not block</strong>: transports should
 * enqueue into a bounded per-connection buffer ({@link BoundedBuffer}) and
 * send from another thread. A listener that throws is dropped from the bus
 * after its error is swallowed (one bad socket must not break a run).
 *
 * <p>
 * Multi-node deployments need a distributed bus (Kafka, Redis); this one only
 * sees events produced in the same JVM.
 */
public final class EventBus {

    private record Sub(String tenantId, String runId, Consumer<RunEvent> listener) {
    }

    private final List<Sub> subs = new CopyOnWriteArrayList<>();

    /**
     * @param runId optional: only events of this run
     * @return handle that unsubscribes when closed
     */
    public AutoCloseable subscribe(String tenantId, String runId, Consumer<RunEvent> listener) {
        Sub s = new Sub(tenantId, runId, listener);
        subs.add(s);
        return () -> subs.remove(s);
    }

    public void publish(RunEvent e) {
        for (Sub s : subs) {
            if (!s.tenantId().equals(e.tenantId()) || (s.runId() != null && !s.runId().equals(e.runId()))) {
                continue;
            }
            try {
                s.listener().accept(e);
            } catch (RuntimeException ex) {
                subs.remove(s);
            }
        }
    }

    public int subscriberCount() {
        return subs.size();
    }

    /**
     * Drop-oldest bounded queue for one slow consumer. When events were
     * discarded, the next {@link #drain} starts with an {@link RunEvent.Type#OVERFLOW}
     * marker so the client knows to re-fetch the run instead of trusting a gapped stream.
     */
    public static final class BoundedBuffer {
        private final java.util.ArrayDeque<RunEvent> q = new java.util.ArrayDeque<>();
        private final int capacity;
        private long dropped;

        public BoundedBuffer(int capacity) {
            if (capacity < 1) {
                throw new IllegalArgumentException("capacity must be >= 1");
            }
            this.capacity = capacity;
        }

        public synchronized void offer(RunEvent e) {
            if (q.size() >= capacity) {
                q.pollFirst();
                dropped++;
            }
            q.addLast(e);
        }

        public synchronized List<RunEvent> drain(String tenantId, java.time.Instant now) {
            List<RunEvent> out = new java.util.ArrayList<>();
            if (dropped > 0) {
                out.add(new RunEvent(RunEvent.Type.OVERFLOW, tenantId, null, null, now, java.util.Map.of("dropped", dropped)));
                dropped = 0;
            }
            out.addAll(q);
            q.clear();
            return out;
        }

        public synchronized int size() {
            return q.size();
        }
    }
}
