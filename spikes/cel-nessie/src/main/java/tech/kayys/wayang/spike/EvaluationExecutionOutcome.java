package tech.kayys.wayang.spike;

/**
 * Explicit host-side result of attempting one provider evaluation.
 *
 * <p>UNKNOWN means the provider invocation began but the host cannot prove
 * whether it stopped or completed. It must not be collapsed into FAILED or
 * automatically retried as though no work occurred.
 */
public sealed interface EvaluationExecutionOutcome
        permits EvaluationExecutionOutcome.Completed,
                EvaluationExecutionOutcome.Rejected,
                EvaluationExecutionOutcome.CancelledBeforeStart,
                EvaluationExecutionOutcome.DeadlineBeforeStart,
                EvaluationExecutionOutcome.Failed,
                EvaluationExecutionOutcome.Unknown {

    record Completed(Object value) implements EvaluationExecutionOutcome { }

    record Rejected(String code) implements EvaluationExecutionOutcome { }

    record CancelledBeforeStart() implements EvaluationExecutionOutcome { }

    record DeadlineBeforeStart() implements EvaluationExecutionOutcome { }

    record Failed(String code) implements EvaluationExecutionOutcome { }

    record Unknown(String code) implements EvaluationExecutionOutcome { }
}
