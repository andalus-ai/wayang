package tech.kayys.wayang.spike;

import java.util.Objects;

/** Provider-neutral execution request; activation remains caller-owned. */
public record EvaluationExecutionRequest(
        EvaluationRequestContext requestContext,
        Object activation) {
    public EvaluationExecutionRequest {
        Objects.requireNonNull(requestContext, "requestContext");
    }
}
