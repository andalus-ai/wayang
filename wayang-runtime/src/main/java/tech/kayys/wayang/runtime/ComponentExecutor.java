package tech.kayys.wayang.runtime;

/**
 * Domain-neutral execution SPI.
 *
 * <p>Implementations should be deterministic for the same request where
 * practical, honor idempotency metadata for side effects, and return expected
 * failures as result values rather than throwing. Runtime policy (retry,
 * timeout, cancellation and persistence) is not delegated to this interface.</p>
 */
public interface ComponentExecutor {
    /** Stable executor family, e.g. {@code wayang.connector} or {@code wayang.agent}. */
    String executorId();

    /** Execute one component operation. */
    ComponentExecutionResult execute(ExecutionRequest request);
}
