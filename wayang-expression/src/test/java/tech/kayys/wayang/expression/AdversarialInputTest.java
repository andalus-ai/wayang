package tech.kayys.wayang.expression;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

/** Hostile expressions must end in an ExpressionException, never a StackOverflowError or an endless loop. */
class AdversarialInputTest {

    private static void refused(String src) {
        try {
            Expressions.evaluate(src, Map.of("x", 1));
            // finishing is fine too; what must not happen is an Error or a hang
        } catch (ExpressionException expected) {
            // good
        }
    }

    @Test
    void deepNestingEndsInAnExpressionException() {
        refused("(".repeat(20_000) + "1" + ")".repeat(20_000));
        refused("-".repeat(50_000) + "1");
        refused("[".repeat(20_000) + "]".repeat(20_000));
        refused("{a:".repeat(5_000) + "1" + "}".repeat(5_000));
        refused("not(".repeat(10_000) + "true" + ")".repeat(10_000));
        refused("if true then ".repeat(5_000) + "1" + " else 2".repeat(5_000));
        refused("1" + " + 1".repeat(100_000));
        refused("x" + ".a".repeat(50_000));
        refused("f(".repeat(10_000) + ")".repeat(10_000));
    }

    @Test
    void deepNestingInAScriptToo() {
        try {
            Script.evaluate("a := " + "(".repeat(20_000) + "1" + ")".repeat(20_000) + "\na", Map.of());
        } catch (ExpressionException expected) {
            // good
        }
    }

    private static long timed(String src, Map<String, ?> vars) {
        long t0 = System.nanoTime();
        try {
            Expressions.evaluate(src, vars);
        } catch (ExpressionException expected) {
            // refused: good
        }
        return (System.nanoTime() - t0) / 1_000_000;
    }

    @Test
    void costlyArithmeticAndRangesAreCutOffQuickly() {
        for (String src : new String[] {"9 ** 99999999", "2 ** 1000000", "(10 ** 1000) ** 1000", "1 / 3 ** 99999", "for i in 1..2000000000 return i", "sum(for i in 1..50000000 return i)",
                "string join(for i in 1..5000000 return \"abcdefghij\", \",\")", "count(1..2000000000)", "1e999999 * 1e999999", "decimal(1, 99999999)",
                "substring(\"abc\", 1, 2000000000)", "[1,2,3][item > 0][item > 0][item > 0]", "x * 123456789012345678901234567890 ** 50"}) {
            assertTrue(timed(src, Map.of("x", 1)) < 5000, src + " must be refused or finish quickly");
        }
    }
}
