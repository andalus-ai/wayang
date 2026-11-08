package tech.kayys.wayang.spike;

import java.time.Duration;
import java.time.Instant;
import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.SynchronousQueue;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;

/** Admission-integrated provider coordinator; leases follow task, not caller, lifetime. */
public final class AdmittedEvaluationCoordinator implements AutoCloseable {
    private final EvaluationAdmissionController admission;
    private final ThreadPoolExecutor workers;
    private final EvaluationExecutionPolicy policy;
    private final EvaluationObserver observer;

    public AdmittedEvaluationCoordinator(EvaluationAdmissionController admission, EvaluationExecutionPolicy policy) {
        this(admission, policy, EvaluationObserver.NOOP);
    }

    public AdmittedEvaluationCoordinator(EvaluationAdmissionController admission,
            EvaluationExecutionPolicy policy, EvaluationObserver observer) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.policy = Objects.requireNonNull(policy, "policy");
        this.observer = Objects.requireNonNull(observer, "observer");
        BlockingQueue<Runnable> queue = policy.maxQueuedTasks() == 0
                ? new SynchronousQueue<>() : new ArrayBlockingQueue<>(policy.maxQueuedTasks());
        workers = new ThreadPoolExecutor(policy.maxWorkers(), policy.maxWorkers(),
                0L, TimeUnit.MILLISECONDS, queue,
                runnable -> {
                    Thread thread = new Thread(runnable, "cel-admitted-provider");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public EvaluationExecutionOutcome execute(EvaluationProviderExecutor provider, EvaluationExecutionRequest request) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        long admissionStarted = System.nanoTime();
        final EvaluationAdmissionController.Lease lease;
        try {
            lease = admission.acquire(request.requestContext());
            emit(EvaluationEvent.Type.ADMISSION_GRANTED, elapsedSince(admissionStarted), null);
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emit(EvaluationEvent.Type.ADMISSION_REJECTED, elapsedSince(admissionStarted), "ADMISSION_INTERRUPTED");
            return new EvaluationExecutionOutcome.Rejected("ADMISSION_INTERRUPTED");
        } catch (EvaluationAdmissionException rejected) {
            emit(EvaluationEvent.Type.ADMISSION_REJECTED, elapsedSince(admissionStarted), rejected.code());
            return new EvaluationExecutionOutcome.Rejected(rejected.code());
        }

        Task task = new Task(provider, request, lease);
        try {
            workers.execute(task);
            emit(EvaluationEvent.Type.EXECUTION_SUBMITTED, 0, null);
        } catch (RejectedExecutionException rejected) {
            task.cancelBeforeStart();
            emit(EvaluationEvent.Type.EXECUTION_REJECTED, 0, "EXECUTION_QUEUE_FULL");
            return new EvaluationExecutionOutcome.Rejected("EXECUTION_QUEUE_FULL");
        }
        try {
            return task.result.get(policy.callerWaitTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            emit(EvaluationEvent.Type.CALLER_WAIT_TIMED_OUT, policy.callerWaitTimeout().toNanos(), null);
            return new EvaluationExecutionOutcome.Unknown("CALLER_WAIT_TIMEOUT_PROVIDER_STATE_UNKNOWN");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            emit(EvaluationEvent.Type.CALLER_INTERRUPTED, 0, null);
            return new EvaluationExecutionOutcome.Unknown("CALLER_INTERRUPTED_PROVIDER_STATE_UNKNOWN");
        } catch (java.util.concurrent.ExecutionException failure) {
            emit(EvaluationEvent.Type.EXECUTION_RESULT_UNAVAILABLE, 0, "TASK_RESULT_UNAVAILABLE");
            return new EvaluationExecutionOutcome.Unknown("TASK_RESULT_UNAVAILABLE");
        }
    }

    public int activeWorkers() { return workers.getActiveCount(); }
    public int queuedTasks() { return workers.getQueue().size(); }
    public boolean isShutdown() { return workers.isShutdown(); }
    public boolean isTerminated() { return workers.isTerminated(); }

    @Override
    public void close() { workers.shutdown(); }

    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) throw new IllegalArgumentException("timeout must not be negative");
        long nanos;
        try { nanos = timeout.toNanos(); }
        catch (ArithmeticException overflow) { nanos = Long.MAX_VALUE; }
        return workers.awaitTermination(nanos, TimeUnit.NANOSECONDS);
    }

    private void emit(EvaluationEvent.Type type, long durationNanos, String reasonCode) {
        try {
            observer.onEvent(new EvaluationEvent(type, Math.max(0, durationNanos), reasonCode));
        } catch (RuntimeException ignored) {
            // Telemetry must never alter admission, provider, or lease semantics.
        }
    }

    private static long elapsedSince(long started) {
        return Math.max(0, System.nanoTime() - started);
    }

    private final class Task implements Runnable {
        private enum State { QUEUED, RUNNING, CANCELLED, FINISHED }
        private final EvaluationProviderExecutor provider;
        private final EvaluationExecutionRequest request;
        private final EvaluationAdmissionController.Lease lease;
        private final AtomicReference<State> state = new AtomicReference<>(State.QUEUED);
        private final CompletableFuture<EvaluationExecutionOutcome> result = new CompletableFuture<>();

        private Task(EvaluationProviderExecutor provider, EvaluationExecutionRequest request,
                EvaluationAdmissionController.Lease lease) {
            this.provider = provider;
            this.request = request;
            this.lease = lease;
        }

        @Override
        public void run() {
            if (!state.compareAndSet(State.QUEUED, State.RUNNING)) return;
            long started = System.nanoTime();
            emit(EvaluationEvent.Type.EXECUTION_STARTED, 0, null);
            EvaluationExecutionOutcome outcome = null;
            try {
                var context = request.requestContext();
                if (context.cancellationToken().isCancelled()) {
                    outcome = new EvaluationExecutionOutcome.CancelledBeforeStart();
                    emit(EvaluationEvent.Type.EXECUTION_CANCELLED_BEFORE_START, 0, null);
                } else {
                    Instant deadline = context.deadline();
                    if (deadline != null && !Instant.now().isBefore(deadline)) {
                        outcome = new EvaluationExecutionOutcome.DeadlineBeforeStart();
                        emit(EvaluationEvent.Type.EXECUTION_DEADLINE_BEFORE_START, 0, null);
                    } else {
                        try {
                            outcome = provider.execute(request);
                            if (outcome == null) outcome = new EvaluationExecutionOutcome.Failed(
                                    "PROVIDER_RETURNED_NULL_OUTCOME");
                        } catch (RuntimeException failure) {
                            outcome = new EvaluationExecutionOutcome.Failed("PROVIDER_EXECUTION_FAILED");
                        } catch (Error fatal) {
                            emit(EvaluationEvent.Type.EXECUTION_FAILED, elapsedSince(started), "PROVIDER_FATAL_ERROR");
                            throw fatal;
                        }
                    }
                }
            } finally {
                state.set(State.FINISHED);
                lease.close();
            }
            if (outcome instanceof EvaluationExecutionOutcome.Completed) {
                emit(EvaluationEvent.Type.EXECUTION_COMPLETED, elapsedSince(started), null);
            } else if (outcome instanceof EvaluationExecutionOutcome.Failed) {
                emit(EvaluationEvent.Type.EXECUTION_FAILED, elapsedSince(started), "PROVIDER_FAILURE");
            } else if (outcome instanceof EvaluationExecutionOutcome.Rejected) {
                emit(EvaluationEvent.Type.EXECUTION_REJECTED, elapsedSince(started), "PROVIDER_REJECTED");
            } else if (outcome instanceof EvaluationExecutionOutcome.Unknown) {
                emit(EvaluationEvent.Type.EXECUTION_RESULT_UNAVAILABLE, elapsedSince(started), "PROVIDER_STATE_UNKNOWN");
            }
            result.complete(outcome);
        }

        private boolean cancelBeforeStart() {
            if (!state.compareAndSet(State.QUEUED, State.CANCELLED)) return false;
            workers.remove(this);
            lease.close();
            emit(EvaluationEvent.Type.EXECUTION_CANCELLED_BEFORE_START, 0, null);
            result.complete(new EvaluationExecutionOutcome.CancelledBeforeStart());
            return true;
        }
    }
}
