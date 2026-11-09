package tech.kayys.wayang.cel.testing;

import tech.kayys.wayang.cel.CelEvaluationResult;

/** Typed comparator; implementations must never coerce failures to false. */
public interface CelTestComparator {
    boolean matches(CelTestExpectation expectation, CelEvaluationResult actual);
}
