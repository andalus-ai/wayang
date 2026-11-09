package tech.kayys.wayang.expression;

import static org.junit.jupiter.api.Assertions.*;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ScriptTest {

    private static Object run(String src, Map<String, ?> vars) {
        return Script.evaluate(src, vars);
    }

    private static void num(String expected, Object actual) {
        assertTrue(actual instanceof BigDecimal, "not a number: " + actual);
        assertEquals(0, new BigDecimal(expected).compareTo((BigDecimal) actual), "expected " + expected + " but was " + actual);
    }

    @Test
    void assignmentsFeedLaterStatementsAndTheLastOneIsTheResult() {
        Map<String, Object> items = Map.of("items", List.of(Map.of("price", 10), Map.of("price", 30)));
        num("44.4", run("total := sum(items.price)\ntax := total * 0.11\ntotal + tax", items));
        assertEquals("review", run("total := 1200; if total > 1000 then \"review\" else \"ok\"", Map.of()));
        num("5", run("a := 2; return a + 3", Map.of()));
        num("7", run("a := 7", Map.of()));
    }

    @Test
    void namesWithSpacesAndShadowingAndInputsWork() {
        num("30", run("`grand total` := base + 20\n`grand total`", Map.of("base", 10)));
        num("99", run("base := 99\nbase", Map.of("base", 10)));
    }

    @Test
    void lineBreaksInsideBracketsAndStringsDoNotEndAStatement() {
        Object r = run("order := {\n  id: \"A;1\",\n  qty: 2\n}\norder.qty * 3", Map.of());
        num("6", r);
        assertEquals("a\nb;c", run("\"a\\nb;c\"", Map.of()).toString().replace("\\n", "\n"));
        assertEquals("x//y", run("\"x//y\" // a comment ; with a semicolon", Map.of()));
        assertEquals(List.of(), Script.split("  \n // only a comment\n"));
    }

    @Test
    void equalityIsNotMistakenForAssignment() {
        assertEquals(true, run("a := 1; a == 1", Map.of()));
        assertEquals(true, run("x == 1", Map.of("x", 1)));
    }

    @Test
    void problemsAreReportedWithTheStatementNumberBeforeAnythingRuns() {
        var e = assertThrows(ExpressionException.class, () -> Script.parse("a := 1\nb := (2 +\nc"));
        assertTrue(e.getMessage().contains("Statement"), e.getMessage());
        assertThrows(ExpressionException.class, () -> Script.parse("   "));
        assertThrows(ExpressionException.class, () -> Script.parse(null));
        assertThrows(ExpressionException.class, () -> Script.parse("a :="));
        assertThrows(ExpressionException.class, () -> Script.parse("return"));
        assertThrows(ExpressionException.class, () -> Script.parse("\"unterminated"));
        assertThrows(ExpressionException.class, () -> Script.parse("`unterminated"));
        assertThrows(ExpressionException.class, () -> Script.parse("1;".repeat(101)));
        assertThrows(ExpressionException.class, () -> Script.parse("1 +".repeat(7000)));
        var r = assertThrows(ExpressionException.class, () -> run("a := 1\nb := 2 + \"x\" * missing.deep.path.length()", Map.of()));
        assertNotNull(r.getMessage());
    }

    @Test
    void oneStepBudgetCoversTheWholeScript() {
        List<Integer> xs = new java.util.ArrayList<>();
        for (int i = 0; i < 200; i++) {
            xs.add(i);
        }
        String one = "some x in xs satisfies some y in xs satisfies false";
        assertEquals(false, run(one, Map.of("xs", xs)), "one statement fits the budget");
        String many = ("a := " + one + "\n").repeat(8) + "a";
        assertThrows(LimitExceededException.class, () -> run(many, Map.of("xs", xs)), "eight of them share it");
    }
}
