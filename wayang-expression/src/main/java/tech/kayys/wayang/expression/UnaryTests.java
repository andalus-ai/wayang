package tech.kayys.wayang.expression;

import java.util.List;

import tech.kayys.wayang.expression.Node.TestNode;

/**
 * A parsed list of DMN-style <em>unary tests</em> ("input entries"), e.g.
 * {@code < 18}, {@code [18..65]}, {@code "gold","silver"},
 * {@code not("blocked")}, or {@code -} (matches anything).
 */
public final class UnaryTests {

    private final String source;
    private final List<TestNode> tests;

    UnaryTests(String source, List<TestNode> tests) {
        this.source = source;
        this.tests = List.copyOf(tests);
    }

    public String source() {
        return source;
    }

    /** @return true when {@code input} satisfies at least one test of the list */
    public boolean matches(Object input, Scope scope) {
        return new Interpreter(source).matches(tests, Values.normalize(input), scope);
    }

    /** @return true for the match-anything entry ({@code -} or an empty cell) */
    public boolean isWildcard() {
        return tests.size() == 1 && tests.get(0) instanceof Node.AnyTest;
    }

    @Override
    public String toString() {
        return source;
    }
}
