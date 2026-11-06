package tech.kayys.wayang.cel.testing;

import java.util.Objects;
import tech.kayys.wayang.cel.CelEvaluationStatus;
import tech.kayys.wayang.cel.CelValue;

/** Explicitly expects either a typed value or a specific failure status. */
public record CelTestExpectation(Kind kind, CelValue expectedValue, CelEvaluationStatus expectedFailure) {
    public enum Kind { VALUE, FAILURE }
    public CelTestExpectation {
        Objects.requireNonNull(kind, "kind");
        if (kind == Kind.VALUE && (expectedValue == null || expectedFailure != null))
            throw new IllegalArgumentException("VALUE requires only expectedValue");
        if (kind == Kind.FAILURE && (expectedFailure == null || expectedValue != null || expectedFailure == CelEvaluationStatus.SUCCESS))
            throw new IllegalArgumentException("FAILURE requires a non-success status");
    }
}
