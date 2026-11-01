package tech.kayys.wayang.expression;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.expression.Node.TestNode;

/**
 * Entry point of the Wayang expression language: a bounded, deterministic,
 * FEEL-style language used for BPMN conditions, DMN decision tables, EIP
 * routing/filtering and data mappings.
 *
 * <p>
 * Design constraints (see the Wayang EIP blueprint, "no arbitrary code
 * execution"): no reflection, no I/O, no regex, no randomness; bounded source
 * length, nesting depth and evaluation steps.
 *
 * <p>
 * Deviations from the DMN FEEL standard (all deliberate, for low-code users):
 * <ul>
 * <li>names with spaces must be written with backticks: {@code `order total`};</li>
 * <li>{@code ==}, {@code &&}, {@code ||} are accepted as aliases for {@code =},
 * {@code and}, {@code or};</li>
 * <li>{@code "id-" + 5} concatenates instead of yielding {@code null};</li>
 * <li>{@code replace}/{@code split} use literal text, not regular
 * expressions;</li>
 * <li>{@code //} starts a line comment.</li>
 * </ul>
 */
public final class Expressions {

    private Expressions() {
    }

    /** Parses an expression; throws {@link ExpressionException} on syntax errors. */
    public static Expression parse(String source) {
        return new ParsedExpression(source, Parser.parseExpression(source));
    }

    /** Parses DMN-style unary tests (input entries). A blank source matches anything. */
    public static UnaryTests parseUnaryTests(String source) {
        if (source == null || source.isBlank()) {
            return new UnaryTests("-", List.of(new Node.AnyTest()));
        }
        return new UnaryTests(source, Parser.parseTests(source));
    }

    /** One-shot parse + evaluate. */
    public static Object evaluate(String source, Map<String, ?> variables) {
        return parse(source).evaluate(Scope.of(variables));
    }

    /** One-shot parse + boolean test (only {@code true} passes). */
    public static boolean test(String source, Map<String, ?> variables) {
        return parse(source).test(Scope.of(variables));
    }

    private record ParsedExpression(String source, Node root) implements Expression {

        @Override
        public Object evaluate(Scope scope) {
            return new Interpreter(source).eval(root, scope);
        }

        @Override
        public Set<String> referencedNames() {
            Set<String> out = new LinkedHashSet<>();
            collect(root, out);
            return out;
        }

        private static void collect(Node n, Set<String> out) {
            switch (n) {
                case Node.Name x -> out.add(x.name());
                case Node.Literal ignored -> {
                }
                case Node.Input ignored -> {
                }
                case Node.Negate x -> collect(x.operand(), out);
                case Node.Binary x -> {
                    collect(x.left(), out);
                    collect(x.right(), out);
                }
                case Node.Between x -> {
                    collect(x.value(), out);
                    collect(x.low(), out);
                    collect(x.high(), out);
                }
                case Node.InTest x -> {
                    collect(x.value(), out);
                    x.tests().forEach(t -> collectTest(t, out));
                }
                case Node.If x -> {
                    collect(x.condition(), out);
                    collect(x.thenBranch(), out);
                    collect(x.elseBranch(), out);
                }
                case Node.Quantified x -> {
                    collect(x.source(), out);
                    collect(x.satisfies(), out);
                }
                case Node.Path x -> collect(x.base(), out);
                case Node.Index x -> {
                    collect(x.base(), out);
                    collect(x.predicate(), out);
                }
                case Node.Call x -> x.args().forEach(arg -> collect(arg, out));
                case Node.ListLiteral x -> x.items().forEach(i -> collect(i, out));
                case Node.ContextLiteral x -> x.entries().forEach(e -> collect(e.value(), out));
                case Node.RangeLiteral x -> {
                    collect(x.start(), out);
                    collect(x.end(), out);
                }
            }
        }

        private static void collectTest(TestNode t, Set<String> out) {
            switch (t) {
                case Node.AnyTest ignored -> {
                }
                case Node.NotTest x -> x.tests().forEach(i -> collectTest(i, out));
                case Node.CompareTest x -> collect(x.operand(), out);
                case Node.ExprTest x -> collect(x.expression(), out);
            }
        }
    }
}
