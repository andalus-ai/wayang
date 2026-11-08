package tech.kayys.wayang.spike;

/**
 * Provider-neutral observation sink. Implementations should be non-blocking.
 * Observer RuntimeExceptions are isolated and never fail an evaluation.
 */
@FunctionalInterface
public interface EvaluationObserver {
    EvaluationObserver NOOP = event -> { };
    void onEvent(EvaluationEvent event);
}
