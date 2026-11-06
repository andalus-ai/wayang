package tech.kayys.wayang.cel;

/** Provider must enforce the supplied evaluation policy. */
public interface CelEvaluatorProvider {
    String providerId();
    String providerVersion();
    CelEvaluationResult evaluate(CompiledCelExpression artifact,
            CelEvaluationContext context, CelEvaluationPolicy policy);
}
