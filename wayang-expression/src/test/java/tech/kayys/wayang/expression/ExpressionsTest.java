package tech.kayys.wayang.expression;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ExpressionsTest {

    private static Object ev(String src) {
        return Expressions.evaluate(src, Map.of());
    }

    private static Object ev(String src, Map<String, ?> vars) {
        return Expressions.evaluate(src, vars);
    }

    private static BigDecimal d(String s) {
        return new BigDecimal(s);
    }

    private static void num(String expected, Object actual) {
        assertTrue(actual instanceof BigDecimal, "not a number: " + actual);
        assertEquals(0, d(expected).compareTo((BigDecimal) actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void arithmeticAndPrecedence() {
        num("7", ev("1 + 2 * 3"));
        num("9", ev("(1 + 2) * 3"));
        num("8", ev("2 ** 3"));
        num("-3", ev("-1 - 2"));
        num("0.3", ev("0.1 + 0.2"));
    }

    @Test
    void comparisonsAndBooleans() {
        assertEquals(true, ev("1 < 2 and 3 >= 3"));
        assertEquals(true, ev("1 = 1.0"));
        assertEquals(true, ev("\"a\" != \"b\""));
        assertEquals(true, ev("1 == 1 && 2 == 2"));
        assertEquals(false, ev("false or false"));
    }

    @Test
    void threeValuedLogic() {
        assertEquals(false, ev("false and x"));
        assertEquals(true, ev("true or x"));
        assertNull(ev("true and x"));
        assertNull(ev("x > 1"));
    }

    @Test
    void variablesAndPaths() {
        Map<String, Object> vars = Map.of("order", Map.of("total", 120, "items", List.of(Map.of("qty", 2), Map.of("qty", 3))));
        num("120", ev("order.total", vars));
        assertEquals(true, ev("order.total > 100", vars));
        num("2", ev("order.items[1].qty", vars));
        assertNull(ev("order.missing.deeper", vars));
    }

    @Test
    void filterAndQuantifiers() {
        Map<String, Object> vars = Map.of("xs", List.of(1, 2, 3, 4, 5));
        assertEquals(List.of(d("4"), d("5")), ev("xs[item > 3]", vars));
        assertEquals(true, ev("some x in xs satisfies x > 4", vars));
        assertEquals(false, ev("every x in xs satisfies x > 1", vars));
    }

    @Test
    void ifThenElse() {
        assertEquals("big", ev("if 5 > 3 then \"big\" else \"small\""));
        assertEquals("small", ev("if 1 > 3 then \"big\" else \"small\""));
    }

    @Test
    void rangesAndIn() {
        assertEquals(true, ev("5 in [1..10]"));
        assertEquals(false, ev("10 in [1..10)"));
        assertEquals(true, ev("5 between 1 and 10"));
        assertEquals(true, ev("\"b\" in (\"a\", \"b\")"));
    }

    @Test
    void stringConcatIsLenient() {
        assertEquals("id-5", ev("\"id-\" + 5"));
        assertEquals("ab", ev("\"a\" + \"b\""));
    }

    @Test
    void builtins() {
        num("5", ev("string length(\"hello\")"));
        assertEquals("HELLO", ev("upper case(\"hello\")"));
        assertEquals("ell", ev("substring(\"hello\", 2, 3)"));
        assertEquals(List.of("a", "b", "c"), ev("split(\"a,b,c\", \",\")"));
        assertEquals("a-b", ev("string join([\"a\",\"b\"], \"-\")"));
        num("6", ev("sum([1,2,3])"));
        num("2", ev("mean([1,2,3])"));
        num("3", ev("max(1,3,2)"));
        num("3", ev("count([1,2,3])"));
        num("3.14", ev("round half up(3.14159, 2)"));
        assertEquals(true, ev("list contains([1,2,3], 2)"));
        assertEquals(List.of(d("1"), d("2"), d("3")), ev("sort([3,1,2])"));
        assertEquals("x", ev("coalesce(null, \"x\")"));
        assertEquals(true, ev("even(4)"));
        num("1", ev("modulo(10, 3)"));
        assertEquals(Map.of("a", d("1"), "b", d("2")), ev("context merge([{a:1},{b:2}])"));
    }

    @Test
    void contextLiteral() {
        assertEquals(Map.of("a", d("1")), ev("{a: 1}"));
    }

    @Test
    void referencedNames() {
        var names = Expressions.parse("order.total > limit and customer.vip").referencedNames();
        assertTrue(names.containsAll(List.of("order", "limit", "customer")));
    }

    @Test
    void syntaxErrorsReportPosition() {
        ExpressionException e = assertThrows(ExpressionException.class, () -> Expressions.parse("1 + "));
        assertNotNull(e.getMessage());
    }

    @Test
    void sourceLengthIsBounded() {
        String big = "1" + " + 1".repeat(5000);
        assertThrows(ExpressionException.class, () -> Expressions.parse(big));
    }

    @Test
    void stepBudgetIsEnforced() {
        var expr = Expressions.parse("some x in xs satisfies some y in xs satisfies some z in xs satisfies false");
        List<Integer> xs = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            xs.add(i);
        }
        assertThrows(LimitExceededException.class,
                () -> expr.evaluate(Scope.of(Map.of("xs", xs)).withMaxSteps(10_000)));
    }

    @Test
    void strictModeThrowsOnUnknownVariable() {
        var expr = Expressions.parse("missing + 1");
        assertThrows(ExpressionException.class, () -> expr.evaluate(Scope.empty().withStrict(true)));
    }

    @Test
    void unaryTests() {
        assertTrue(Expressions.parseUnaryTests("< 18").matches(10, Scope.empty()));
        assertFalse(Expressions.parseUnaryTests("< 18").matches(20, Scope.empty()));
        assertTrue(Expressions.parseUnaryTests("[18..65]").matches(30, Scope.empty()));
        assertTrue(Expressions.parseUnaryTests("\"gold\",\"silver\"").matches("silver", Scope.empty()));
        assertTrue(Expressions.parseUnaryTests("not(\"blocked\")").matches("ok", Scope.empty()));
        assertFalse(Expressions.parseUnaryTests("not(\"blocked\")").matches("blocked", Scope.empty()));
        assertTrue(Expressions.parseUnaryTests("-").matches("anything", Scope.empty()));
        assertTrue(Expressions.parseUnaryTests("").isWildcard());
        assertTrue(Expressions.parseUnaryTests(">= 100").matches(100, Scope.empty()));
    }

    @Test
    void unknownFunctionsAreParseErrorsWithASuggestion() {
        var e = assertThrows(ExpressionException.class, () -> Expressions.parse("lower(name) = \"a\""));
        assertTrue(e.getMessage().contains("Unknown function 'lower'") && e.getMessage().contains("lower case"), e.getMessage());
        assertThrows(ExpressionException.class, () -> Expressions.parse("count(x) + nosuchthing(1)"));
        assertEquals("a", Expressions.evaluate("lower case(\"A\")", java.util.Map.of()));
        assertEquals(java.math.BigDecimal.valueOf(3), Expressions.evaluate("count([1,2,3])", java.util.Map.of()));
    }

    @Test
    void theKnownFunctionListMatchesTheDispatchSwitch() throws Exception {
        java.nio.file.Path dir = java.nio.file.Path.of("").toAbsolutePath();
        while (dir != null && !java.nio.file.Files.exists(dir.resolve("wayang-expression/src/main/java/tech/kayys/wayang/expression/Builtins.java"))) {
            dir = dir.getParent();
        }
        assertNotNull(dir);
        String src = java.nio.file.Files.readString(dir.resolve("wayang-expression/src/main/java/tech/kayys/wayang/expression/Builtins.java"));
        String body = src.substring(src.indexOf("private static Object dispatch("));
        body = body.substring(0, body.indexOf("return it.fail(scope, \"Unknown function"));
        java.util.Set<String> cases = new java.util.TreeSet<>();
        var m = java.util.regex.Pattern.compile("case \"([^\"]+)\"").matcher(body);
        while (m.find()) {
            cases.add(m.group(1));
        }
        assertTrue(cases.size() > 50, "found the switch labels");
        assertEquals(cases, new java.util.TreeSet<>(Builtins.NAMES), "Builtins.NAMES and the dispatch switch differ");
    }

}
