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

/**
 * Admission-integrated provider coordinator. The admission lease follows the
 * task lifetime, never the caller's wait lifetime.
 */
public final class AdmittedEvaluationCoordinator implements AutoCloseable {
    private final EvaluationAdmissionController admission;
    private final ThreadPoolExecutor workers;
    private final EvaluationExecutionPolicy policy;

    public AdmittedEvaluationCoordinator(
            EvaluationAdmissionController admission, EvaluationExecutionPolicy policy) {
        this.admission = Objects.requireNonNull(admission, "admission");
        this.policy = Objects.requireNonNull(policy, "policy");
        BlockingQueue<Runnable> queue = policy.maxQueuedTasks() == 0
                ? new SynchronousQueue<>()
                : new ArrayBlockingQueue<>(policy.maxQueuedTasks());
        workers = new ThreadPoolExecutor(policy.maxWorkers(), policy.maxWorkers(),
                0L, TimeUnit.MILLISECONDS, queue,
                runnable -> {
                    Thread thread = new Thread(runnable, "cel-admitted-provider");
                    thread.setDaemon(true);
                    return thread;
                }, new ThreadPoolExecutor.AbortPolicy());
    }

    public EvaluationExecutionOutcome execute(
            EvaluationProviderExecutor provider, EvaluationExecutionRequest request) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        final EvaluationAdmissionController.Lease lease;
        try {
            lease = admission.acquire(request.requestContext());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new EvaluationExecutionOutcome.Rejected("ADMISSION_INTERRUPTED");
        } catch (EvaluationAdmissionException rejected) {
            return new EvaluationExecutionOutcome.Rejected(rejected.code());
        }

        Task task = new Task(provider, request, lease);
        try {
            workers.execute(task);
        } catch (RejectedExecutionException rejected) {
            task.cancelBeforeStart();
            return new EvaluationExecutionOutcome.Rejected("EXECUTION_QUEUE_FULL");
        }
        try {
            return task.result.get(policy.callerWaitTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (java.util.concurrent.TimeoutException timeout) {
            return new EvaluationExecutionOutcome.Unknown("CALLER_WAIT_TIMEOUT_PROVIDER_STATE_UNKNOWN");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            return new EvaluationExecutionOutcome.Unknown("CALLER_INTERRUPTED_PROVIDER_STATE_UNKNOWN");
        } catch (java.util.concurrent.ExecutionException failure) {
            return new EvaluationExecutionOutcome.Unknown("TASK_RESULT_UNAVAILABLE");
        }
    }

    public int activeWorkers() { return workers.getActiveCount(); }
    public int queuedTasks() { return workers.getQueue().size(); }
    public boolean isShutdown() { return workers.isShutdown(); }
    public boolean isTerminated() { return workers.isTerminated(); }

    /**
     * Stops accepting work but drains already accepted tasks. A running provider
     * is not interrupted and its admission lease remains held until it returns.
     */
    @Override
    public void close() { workers.shutdown(); }

    /**
     * Waits for orderly shutdown to finish. This method never interrupts a
     * provider or force-releases its admission lease.
     *
     * @param timeout maximum time to wait; zero performs a non-blocking check
     * @return true only when every accepted task has terminated
     */
    public boolean awaitTermination(Duration timeout) throws InterruptedException {
        Objects.requireNonNull(timeout, "timeout");
        if (timeout.isNegative()) {
            throw new IllegalArgumentException("timeout must not be negative");
        }
        long nanos;
        try {
            nanos = timeout.toNanos();
        } catch (ArithmeticException overflow) {
            nanos = Long.MAX_VALUE;
        }
        return workers.awaitTermination(nanos, TimeUnit.NANOSECONDS);
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
            EvaluationExecutionOutcome outcome = null;
            try {
                var context = request.requestContext();
                if (context.cancellationToken().isCancelled()) {
                    outcome = new EvaluationExecutionOutcome.CancelledBeforeStart();
                } else {
                    Instant deadline = context.deadline();
                    if (deadline != null && !Instant.now().isBefore(deadline)) {
                        outcome = new EvaluationExecutionOutcome.DeadlineBeforeStart();
                    } else {
                        try {
                            outcome = provider.execute(request);
                            if (outcome == null) {
                                outcome = new EvaluationExecutionOutcome.Failed(
                                        "PROVIDER_RETURNED_NULL_OUTCOME");
                            }
                        } catch (RuntimeException failure) {
                            outcome = new EvaluationExecutionOutcome.Failed(
                                    "PROVIDER_EXECUTION_FAILED");
                        }
                    }
                }
            } finally {
                state.set(State.FINISHED);
                lease.close();
            }
            // Publish completion only after lease accounting reflects task termination.
            result.complete(outcome);
        }

        private boolean cancelBeforeStart() {
            if (!state.compareAndSet(State.QUEUED, State.CANCELLED)) return false;
            workers.remove(this);
            lease.close();
            result.complete(new EvaluationExecutionOutcome.CancelledBeforeStart());
            return true;
        }
    }
}
