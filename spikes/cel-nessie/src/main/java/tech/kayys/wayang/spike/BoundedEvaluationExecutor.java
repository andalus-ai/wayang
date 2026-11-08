package tech.kayys.wayang.spike;

import java.util.Objects;
import java.util.concurrent.ArrayBlockingQueue;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.Future;
import java.util.concurrent.RejectedExecutionException;
import java.util.concurrent.ThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Bounded worker adapter for provider calls.
 *
 * <p>Caller wait is bounded, but Java cannot forcibly stop arbitrary provider
 * code. On timeout this class returns Unknown and deliberately does not release
 * a worker slot until the provider task actually exits. cancel(false) only
 * prevents a task that has not started; it is not provider interruption.
 */
public final class BoundedEvaluationExecutor implements AutoCloseable {
    private final ThreadPoolExecutor workers;
    private final EvaluationExecutionPolicy policy;
    private final AtomicBoolean closed = new AtomicBoolean();

    public BoundedEvaluationExecutor(EvaluationExecutionPolicy policy) {
        this.policy = Objects.requireNonNull(policy, "policy");
        this.workers = new ThreadPoolExecutor(
                policy.maxWorkers(),
                policy.maxWorkers(),
                0L,
                TimeUnit.MILLISECONDS,
                new ArrayBlockingQueue<>(Math.max(1, policy.maxQueuedTasks())),
                runnable -> {
                    Thread thread = new Thread(runnable, "cel-provider-worker");
                    thread.setDaemon(true);
                    return thread;
                },
                new ThreadPoolExecutor.AbortPolicy());
    }

    public EvaluationExecutionOutcome execute(
            EvaluationProviderExecutor provider,
            EvaluationExecutionRequest request) {
        Objects.requireNonNull(provider, "provider");
        Objects.requireNonNull(request, "request");
        if (closed.get()) {
            return new EvaluationExecutionOutcome.Rejected("EXECUTOR_CLOSED");
        }
        var token = request.requestContext().cancellationToken();
        if (token.isCancelled()) {
            return new EvaluationExecutionOutcome.CancelledBeforeStart();
        }
        var deadline = request.requestContext().deadline();
        if (deadline != null && !java.time.Instant.now().isBefore(deadline)) {
            return new EvaluationExecutionOutcome.DeadlineBeforeStart();
        }

        Future<EvaluationExecutionOutcome> future;
        try {
            future = workers.submit(() -> {
                if (token.isCancelled()) {
                    return new EvaluationExecutionOutcome.CancelledBeforeStart();
                }
                if (deadline != null && !java.time.Instant.now().isBefore(deadline)) {
                    return new EvaluationExecutionOutcome.DeadlineBeforeStart();
                }
                try {
                    EvaluationExecutionOutcome outcome = provider.execute(request);
                    return outcome == null
                            ? new EvaluationExecutionOutcome.Failed("PROVIDER_RETURNED_NULL_OUTCOME")
                            : outcome;
                } catch (Throwable failure) {
                    // Do not expose provider messages, activation values, or tenant data.
                    return new EvaluationExecutionOutcome.Failed("PROVIDER_EXECUTION_FAILED");
                }
            });
        } catch (RejectedExecutionException rejected) {
            return new EvaluationExecutionOutcome.Rejected("EXECUTION_QUEUE_FULL");
        }

        try {
            return future.get(policy.callerWaitTimeout().toNanos(), TimeUnit.NANOSECONDS);
        } catch (TimeoutException timeout) {
            // cancel(false) can stop only a task that has not started. Running
            // provider code remains responsible for its own cancellation contract.
            future.cancel(false);
            return new EvaluationExecutionOutcome.Unknown("CALLER_WAIT_TIMEOUT_PROVIDER_STATE_UNKNOWN");
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            future.cancel(false);
            return new EvaluationExecutionOutcome.Unknown("CALLER_INTERRUPTED_PROVIDER_STATE_UNKNOWN");
        } catch (ExecutionException failure) {
            return new EvaluationExecutionOutcome.Failed("EXECUTION_WRAPPER_FAILED");
        }
    }

    public int activeWorkers() {
        return workers.getActiveCount();
    }

    public int queuedTasks() {
        return workers.getQueue().size();
    }

    @Override
    public void close() {
        if (closed.compareAndSet(false, true)) {
            workers.shutdown();
        }
    }
}
