package tech.kayys.wayang.cel.testing;

import java.math.BigDecimal;
import java.util.Arrays;
import java.util.Map;
import java.util.Objects;
import java.util.stream.IntStream;

import tech.kayys.wayang.cel.CelEvaluationResult;
import tech.kayys.wayang.cel.CelEvaluationStatus;
import tech.kayys.wayang.cel.CelValue;

/** Default structural comparator for typed CEL values. */
public final class DefaultCelTestComparator implements CelTestComparator {
    @Override
    public boolean matches(CelTestExpectation expectation, CelEvaluationResult actual) {
        Objects.requireNonNull(expectation, "expectation");
        Objects.requireNonNull(actual, "actual");
        if (expectation.kind() == CelTestExpectation.Kind.FAILURE) {
            return actual.status() == expectation.expectedFailure();
        }
        return actual.status() == CelEvaluationStatus.SUCCESS
                && equivalent(expectation.expectedValue(), actual.value());
    }

    private boolean equivalent(CelValue expected, CelValue actual) {
        if (expected == actual) return true;
        if (expected == null || actual == null || expected.getClass() != actual.getClass()) return false;
        if (expected instanceof CelValue.DecimalValue left && actual instanceof CelValue.DecimalValue right)
            return left.value().compareTo(right.value()) == 0;
        if (expected instanceof CelValue.BytesValue left && actual instanceof CelValue.BytesValue right)
            return Arrays.equals(left.value(), right.value());
        if (expected instanceof CelValue.ListValue left && actual instanceof CelValue.ListValue right)
            return left.values().size() == right.values().size()
                    && IntStream.range(0, left.values().size())
                    .allMatch(i -> equivalent(left.values().get(i), right.values().get(i)));
        if (expected instanceof CelValue.MapValue left && actual instanceof CelValue.MapValue right) {
            Map<String, CelValue> a = left.values(), b = right.values();
            return a.keySet().equals(b.keySet())
                    && a.entrySet().stream().allMatch(e -> equivalent(e.getValue(), b.get(e.getKey())));
        }
        return expected.equals(actual);
    }
}
