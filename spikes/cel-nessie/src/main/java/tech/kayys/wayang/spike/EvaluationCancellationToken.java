package tech.kayys.wayang.spike;

import java.util.concurrent.atomic.AtomicBoolean;

/**
 * Cooperative cancellation signal for host code surrounding an evaluation.
 *
 * <p>This token does not interrupt CEL-Java Program.eval and must not be
 * represented as proof that an in-progress provider call has stopped.
 */
public final class EvaluationCancellationToken {
    private final AtomicBoolean cancelled = new AtomicBoolean();

    public boolean cancel() {
        return cancelled.compareAndSet(false, true);
    }

    public boolean isCancelled() {
        return cancelled.get();
    }

    public void throwIfCancelled() {
        if (isCancelled()) {
            throw new EvaluationAdmissionException("EVALUATION_CANCELLED");
        }
    }
}
