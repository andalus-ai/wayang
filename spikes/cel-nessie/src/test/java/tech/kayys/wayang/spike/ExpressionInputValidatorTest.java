package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;

class ExpressionInputValidatorTest {

    private static ExpressionInputValidator validator() {
        return new ExpressionInputValidator(new ExpressionInputValidator.Limits(
                32, 2, 3, 3, 12, 16, 64));
    }

    @Test
    void acceptsSmallPrimitiveAndNestedActivationValues() {
        validator().validateSource("customer.age > 18");
        validator().validateVariables(Map.of(
                "customer", Map.of("age", 21L, "name", "Ari"),
                "active", true));
        validator().validateResult(List.of("ok", 1L, false));
    }

    @Test
    void rejectsOversizedSourceAndTooManyVariables() {
        assertEquals("SOURCE_BYTES_LIMIT", assertThrows(ExpressionInputLimitException.class,
                () -> validator().validateSource("x".repeat(33))).code());
        assertEquals("VARIABLE_COUNT_LIMIT", assertThrows(ExpressionInputLimitException.class,
                () -> validator().validateVariables(Map.of("a", 1, "b", 2, "c", 3))).code());
    }

    @Test
    void rejectsOversizedStringsAndCollections() {
        assertEquals("STRING_BYTES_LIMIT", assertThrows(ExpressionInputLimitException.class,
                () -> validator().validateVariables(Map.of("a", "x".repeat(17)))).code());
        assertEquals("COLLECTION_ENTRY_LIMIT", assertThrows(ExpressionInputLimitException.class,
                () -> validator().validateVariables(Map.of("a", List.of(1, 2, 3, 4)))).code());
    }

    @Test
    void rejectsCyclesAndUnsupportedHostObjects() {
        List<Object> cyclic = new ArrayList<>();
        cyclic.add(cyclic);
        assertEquals("CYCLIC_VALUE_NOT_SUPPORTED", assertThrows(ExpressionInputLimitException.class,
                () -> validator().validateResult(cyclic)).code());
        assertEquals("UNSUPPORTED_HOST_VALUE_TYPE", assertThrows(ExpressionInputLimitException.class,
                () -> validator().validateResult(new Object())).code());
    }

    @Test
    void rejectsNonStringMapKeysAndExcessiveDepth() {
        assertEquals("MAP_KEYS_MUST_BE_STRINGS", assertThrows(ExpressionInputLimitException.class,
                () -> validator().validateResult(Map.of(1, "bad"))).code());
        assertEquals("VALUE_DEPTH_LIMIT", assertThrows(ExpressionInputLimitException.class,
                () -> validator().validateResult(Map.of("a", Map.of("b", Map.of("c", Map.of("d", 1)))))).code());
    }

    @Test
    void rejectsUnsupportedLimitsAtConstruction() {
        assertThrows(IllegalArgumentException.class,
                () -> new ExpressionInputValidator.Limits(0, 1, 1, 1, 1, 1, 1));
    }
}
