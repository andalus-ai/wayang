package tech.kayys.wayang.spike;

/**
 * Host boundary for one evaluation invocation.
 *
 * <p>Implementations must document whether provider calls are thread-safe,
 * cancellable, deadline-aware, and safe to reuse. The default contract makes
 * none of those claims. A returned UNKNOWN outcome means execution may have
 * continued or completed after the caller lost certainty.
 */
@FunctionalInterface
public interface EvaluationProviderExecutor {
    EvaluationExecutionOutcome execute(EvaluationExecutionRequest request);
}
