package tech.kayys.wayang.spike;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.LinkedList;
import java.util.Map;
import java.util.Objects;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.Condition;
import java.util.concurrent.locks.ReentrantLock;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded, fair admission for host-managed expression evaluations.
 *
 * <p>The controller limits admitted evaluations and queued waiters globally
 * and per trusted tenant. It does not execute expressions, preempt provider
 * work, or impose CPU/heap limits. A lease must be closed in a try-with-resources
 * block around the entire provider evaluation.
 */
public final class EvaluationAdmissionController implements AutoCloseable {
    private static final long MAX_WAIT_SLICE_NANOS = TimeUnit.MILLISECONDS.toNanos(50);

    private final EvaluationAdmissionPolicy policy;
    private final Clock clock;
    private final ReentrantLock lock = new ReentrantLock(true);
    private final Condition changed = lock.newCondition();
    private final LinkedList<Waiter> queue = new LinkedList<>();
    private final Map<String, Integer> activeByTenant = new HashMap<>();
    private int active;
    private boolean closed;

    public EvaluationAdmissionController(EvaluationAdmissionPolicy policy) {
        this(policy, Clock.systemUTC());
    }

    EvaluationAdmissionController(EvaluationAdmissionPolicy policy, Clock clock) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Waits only within the configured queue and request deadlines.
     * Interruption removes the waiter and is propagated to the caller.
     */
    public Lease acquire(EvaluationRequestContext request) throws InterruptedException {
        Objects.requireNonNull(request, "request");
        request.cancellationToken().throwIfCancelled();
        if (request.deadline() != null && !clock.instant().isBefore(request.deadline())) {
            throw new EvaluationAdmissionException("REQUEST_DEADLINE_EXCEEDED");
        }

        long queueDeadline = saturatingAdd(System.nanoTime(), policy.maxQueueWait().toNanos());
        Waiter waiter = new Waiter(request.tenantId());
        lock.lockInterruptibly();
        try {
            ensureOpen();
            if (queue.isEmpty() && canAdmit(request.tenantId())) {
                return grant(request.tenantId());
            }
            if (policy.maxQueued() == 0 || queue.size() >= policy.maxQueued()) {
                throw new EvaluationAdmissionException("GLOBAL_QUEUE_FULL");
            }
            if (queuedFor(request.tenantId()) >= policy.maxQueuedPerTenant()) {
                throw new EvaluationAdmissionException("TENANT_QUEUE_FULL");
            }
            queue.addLast(waiter);
            try {
                while (true) {
                    ensureOpen();
                    request.cancellationToken().throwIfCancelled();
                    if (request.deadline() != null && !clock.instant().isBefore(request.deadline())) {
                        throw new EvaluationAdmissionException("REQUEST_DEADLINE_EXCEEDED");
                    }
                    if (System.nanoTime() >= queueDeadline) {
                        throw new EvaluationAdmissionException("QUEUE_WAIT_TIMEOUT");
                    }
                    if (isFirstEligible(waiter) && canAdmit(request.tenantId())) {
                        queue.remove(waiter);
                        Lease lease = grant(request.tenantId());
                        changed.signalAll();
                        return lease;
                    }
                    long remaining = queueDeadline - System.nanoTime();
                    if (request.deadline() != null) {
                        long requestRemaining = Duration.between(clock.instant(), request.deadline()).toNanos();
                        remaining = Math.min(remaining, requestRemaining);
                    }
                    changed.awaitNanos(Math.min(MAX_WAIT_SLICE_NANOS, Math.max(1L, remaining)));
                }
            } catch (RuntimeException | InterruptedException failure) {
                queue.remove(waiter);
                changed.signalAll();
                throw failure;
            }
        } finally {
            lock.unlock();
        }
    }

    private boolean isFirstEligible(Waiter target) {
        for (Waiter candidate : queue) {
            if (candidate == target) {
                return true;
            }
            if (canAdmit(candidate.tenantId)) {
                return false;
            }
        }
        return false;
    }

    private int queuedFor(String tenantId) {
        int count = 0;
        for (Waiter waiter : queue) {
            if (waiter.tenantId.equals(tenantId)) {
                count++;
            }
        }
        return count;
    }

    private boolean canAdmit(String tenantId) {
        return active < policy.maxConcurrent()
                && activeByTenant.getOrDefault(tenantId, 0) < policy.maxConcurrentPerTenant();
    }

    private Lease grant(String tenantId) {
        active++;
        activeByTenant.merge(tenantId, 1, Integer::sum);
        return new Lease(this, tenantId);
    }

    private void release(String tenantId) {
        lock.lock();
        try {
            int tenantActive = activeByTenant.getOrDefault(tenantId, 0);
            if (tenantActive < 1 || active < 1) {
                throw new IllegalStateException("Evaluation lease accounting underflow");
            }
            active--;
            if (tenantActive == 1) {
                activeByTenant.remove(tenantId);
            } else {
                activeByTenant.put(tenantId, tenantActive - 1);
            }
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private void ensureOpen() {
        if (closed) {
            throw new EvaluationAdmissionException("ADMISSION_CONTROLLER_CLOSED");
        }
    }

    public Snapshot snapshot() {
        lock.lock();
        try {
            Map<String, Integer> queuedByTenant = new HashMap<>();
            for (Waiter waiter : queue) {
                queuedByTenant.merge(waiter.tenantId, 1, Integer::sum);
            }
            return new Snapshot(active, queue.size(), Map.copyOf(activeByTenant), Map.copyOf(queuedByTenant), closed);
        } finally {
            lock.unlock();
        }
    }

    @Override
    public void close() {
        lock.lock();
        try {
            closed = true;
            changed.signalAll();
        } finally {
            lock.unlock();
        }
    }

    private static long saturatingAdd(long left, long right) {
        if (right > 0 && left > Long.MAX_VALUE - right) {
            return Long.MAX_VALUE;
        }
        return left + right;
    }

    private record Waiter(String tenantId) { }

    public record Snapshot(
            int active,
            int queued,
            Map<String, Integer> activeByTenant,
            Map<String, Integer> queuedByTenant,
            boolean closed) { }

    public static final class Lease implements AutoCloseable {
        private final EvaluationAdmissionController owner;
        private final String tenantId;
        private final AtomicBoolean released = new AtomicBoolean();

        private Lease(EvaluationAdmissionController owner, String tenantId) {
            this.owner = owner;
            this.tenantId = tenantId;
        }

        @Override
        public void close() {
            if (released.compareAndSet(false, true)) {
                owner.release(tenantId);
            }
        }

        public boolean isReleased() {
            return released.get();
        }
    }
}
