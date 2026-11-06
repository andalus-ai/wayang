package tech.kayys.wayang.cel.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.cel.CelEvaluationResult;
import tech.kayys.wayang.cel.CelEvaluationStatus;
import tech.kayys.wayang.cel.CelValue;

class DefaultCelTestComparatorTest {
    private final DefaultCelTestComparator comparator = new DefaultCelTestComparator();

    @Test
    void falseDoesNotMatchTrue() {
        var expected = new CelTestExpectation(CelTestExpectation.Kind.VALUE,
                new CelValue.BoolValue(true), null);
        assertFalse(comparator.matches(expected,
                CelEvaluationResult.success(new CelValue.BoolValue(false), 10)));
    }

    @Test
    void explicitNullIsAValue() {
        var expected = new CelTestExpectation(CelTestExpectation.Kind.VALUE,
                new CelValue.NullValue(), null);
        assertTrue(comparator.matches(expected,
                CelEvaluationResult.success(new CelValue.NullValue(), 10)));
    }

    @Test
    void failureMustMatchExactExpectedStatus() {
        var expected = new CelTestExpectation(CelTestExpectation.Kind.FAILURE,
                null, CelEvaluationStatus.TYPE_ERROR);
        assertTrue(comparator.matches(expected,
                CelEvaluationResult.failure(CelEvaluationStatus.TYPE_ERROR, List.of("CEL-TYPE"), 10)));
        assertFalse(comparator.matches(expected,
                CelEvaluationResult.failure(CelEvaluationStatus.EVALUATION_ERROR, List.of("CEL-EVAL"), 10)));
    }

    @Test
    void decimalsCompareByNumericValue() {
        var expected = new CelTestExpectation(CelTestExpectation.Kind.VALUE,
                new CelValue.DecimalValue(new BigDecimal("1.0")), null);
        assertTrue(comparator.matches(expected, CelEvaluationResult.success(
                new CelValue.DecimalValue(new BigDecimal("1.00")), 10)));
    }

    @Test
    void nestedMapsAndListsCompareStructurally() {
        CelValue expectedValue = new CelValue.MapValue(Map.of("items",
                new CelValue.ListValue(List.of(new CelValue.StringValue("a")))));
        CelValue actualValue = new CelValue.MapValue(Map.of("items",
                new CelValue.ListValue(List.of(new CelValue.StringValue("a")))));
        var expected = new CelTestExpectation(CelTestExpectation.Kind.VALUE, expectedValue, null);
        assertTrue(comparator.matches(expected, CelEvaluationResult.success(actualValue, 10)));
    }
}
