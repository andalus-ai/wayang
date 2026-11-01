package tech.kayys.wayang.expression;

import java.util.List;

/** Abstract syntax tree of the expression language (internal). */
sealed interface Node {

    record Literal(Object value) implements Node {
    }

    record Name(String name, int pos) implements Node {
    }

    /** The implicit input value {@code ?} of a unary test. */
    record Input() implements Node {
    }

    record Negate(Node operand, int pos) implements Node {
    }

    enum BinOp {
        ADD, SUB, MUL, DIV, POW, EQ, NE, LT, LE, GT, GE, AND, OR
    }

    record Binary(BinOp op, Node left, Node right, int pos) implements Node {
    }

    record Between(Node value, Node low, Node high, int pos) implements Node {
    }

    record InTest(Node value, List<TestNode> tests, int pos) implements Node {
    }

    record If(Node condition, Node thenBranch, Node elseBranch, int pos) implements Node {
    }

    record Quantified(boolean some, String variable, Node source, Node satisfies, int pos) implements Node {
    }

    record Path(Node base, String name, int pos) implements Node {
    }

    /** {@code list[expr]}: a 1-based index when the predicate yields a number, otherwise a filter. */
    record Index(Node base, Node predicate, int pos) implements Node {
    }

    record Call(String function, List<Node> args, int pos) implements Node {
    }

    record ListLiteral(List<Node> items) implements Node {
    }

    record ContextEntry(String key, Node value) {
    }

    record ContextLiteral(List<ContextEntry> entries) implements Node {
    }

    record RangeLiteral(Node start, boolean startIncluded, Node end, boolean endIncluded) implements Node {
    }

    /** One positive or negated entry of a unary-tests list. */
    sealed interface TestNode {
    }

    /** The "-" test: matches anything. */
    record AnyTest() implements TestNode {
    }

    record NotTest(List<TestNode> tests) implements TestNode {
    }

    record CompareTest(BinOp op, Node operand) implements TestNode {
    }

    record ExprTest(Node expression) implements TestNode {
    }
}
