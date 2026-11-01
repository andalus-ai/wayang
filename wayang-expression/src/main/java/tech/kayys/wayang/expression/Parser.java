package tech.kayys.wayang.expression;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import tech.kayys.wayang.expression.Lexer.Token;
import tech.kayys.wayang.expression.Lexer.Type;
import tech.kayys.wayang.expression.Node.BinOp;
import tech.kayys.wayang.expression.Node.TestNode;

/**
 * Recursive-descent parser. Precedence, lowest to highest:
 * {@code if/some/every} &lt; {@code or} &lt; {@code and} &lt;
 * comparison/{@code between}/{@code in} &lt; {@code + -} &lt; {@code * /} &lt;
 * {@code **} &lt; unary minus &lt; path/index/call.
 */
final class Parser {

    static final int MAX_SOURCE_LENGTH = 10_000;
    static final int MAX_DEPTH = 64;

    private static final Set<String> RESERVED = Set.of(
            "then", "else", "and", "or", "in", "between", "satisfies");

    private final String src;
    private final List<Token> tokens;
    private int p;
    private int depth;

    private Parser(String src) {
        if (src == null) {
            throw new ExpressionException("Expression must not be null", null, -1);
        }
        if (src.length() > MAX_SOURCE_LENGTH) {
            throw new ExpressionException(
                    "Expression exceeds the maximum length of " + MAX_SOURCE_LENGTH + " characters", src, -1);
        }
        this.src = src;
        this.tokens = Lexer.tokenize(src);
    }

    static Node parseExpression(String src) {
        Parser parser = new Parser(src);
        Node node = parser.expression();
        parser.expectEof();
        return node;
    }

    static List<TestNode> parseTests(String src) {
        Parser parser = new Parser(src);
        List<TestNode> tests = parser.testList();
        parser.expectEof();
        return tests;
    }

    // ------------------------------------------------------------------ helpers

    private Token peek() {
        return tokens.get(p);
    }

    private Token peekAt(int offset) {
        return tokens.get(Math.min(p + offset, tokens.size() - 1));
    }

    private Token next() {
        Token t = tokens.get(p);
        if (t.type() != Type.EOF) {
            p++;
        }
        return t;
    }

    private boolean atOp(String op) {
        return peek().isOp(op);
    }

    private boolean atWord(String word) {
        Token t = peek();
        return t.isKeyword(word) || (t.type() == Type.OP && t.text().equals(word));
    }

    private boolean matchOp(String op) {
        if (atOp(op)) {
            next();
            return true;
        }
        return false;
    }

    private boolean matchWord(String word) {
        if (atWord(word)) {
            next();
            return true;
        }
        return false;
    }

    private void expectOp(String op) {
        if (!matchOp(op)) {
            throw error("Expected '" + op + "' but found " + describe(peek()), peek());
        }
    }

    private void expectWord(String word) {
        if (!matchWord(word)) {
            throw error("Expected '" + word + "' but found " + describe(peek()), peek());
        }
    }

    private void expectEof() {
        if (peek().type() != Type.EOF) {
            throw error("Unexpected " + describe(peek()), peek());
        }
    }

    private static String describe(Token t) {
        return t.type() == Type.EOF ? "end of expression" : "'" + t.text() + "'";
    }

    private ExpressionException error(String message, Token at) {
        return new ExpressionException(message, src, at.pos());
    }

    private void enter() {
        if (++depth > MAX_DEPTH) {
            throw new ExpressionException("Expression is nested deeper than " + MAX_DEPTH + " levels", src,
                    peek().pos());
        }
    }

    private void leave() {
        depth--;
    }

    // ------------------------------------------------------------------ grammar

    private Node expression() {
        enter();
        try {
            Token t = peek();
            if (t.isKeyword("if")) {
                return ifExpression();
            }
            if (t.isKeyword("some") || t.isKeyword("every")) {
                return quantified();
            }
            return disjunction();
        } finally {
            leave();
        }
    }

    private Node ifExpression() {
        int pos = next().pos();
        Node cond = expression();
        expectWord("then");
        Node thenBranch = expression();
        expectWord("else");
        Node elseBranch = expression();
        return new Node.If(cond, thenBranch, elseBranch, pos);
    }

    private Node quantified() {
        Token kw = next();
        Token var = next();
        if (var.type() != Type.IDENT && var.type() != Type.QUOTED_IDENT) {
            throw error("Expected a variable name after '" + kw.text() + "'", var);
        }
        expectWord("in");
        Node source = disjunction();
        expectWord("satisfies");
        Node body = expression();
        return new Node.Quantified(kw.text().equals("some"), var.text(), source, body, kw.pos());
    }

    private Node disjunction() {
        Node left = conjunction();
        while (atWord("or")) {
            int pos = next().pos();
            left = new Node.Binary(BinOp.OR, left, conjunction(), pos);
        }
        return left;
    }

    private Node conjunction() {
        Node left = comparison();
        while (atWord("and")) {
            int pos = next().pos();
            left = new Node.Binary(BinOp.AND, left, comparison(), pos);
        }
        return left;
    }

    private Node comparison() {
        Node left = additive();
        Token t = peek();
        if (t.type() == Type.OP) {
            BinOp op = switch (t.text()) {
                case "=" -> BinOp.EQ;
                case "!=" -> BinOp.NE;
                case "<" -> BinOp.LT;
                case "<=" -> BinOp.LE;
                case ">" -> BinOp.GT;
                case ">=" -> BinOp.GE;
                default -> null;
            };
            if (op != null) {
                next();
                return new Node.Binary(op, left, additive(), t.pos());
            }
        }
        if (t.isKeyword("between")) {
            next();
            Node low = additive();
            expectWord("and");
            Node high = additive();
            return new Node.Between(left, low, high, t.pos());
        }
        if (t.isKeyword("in")) {
            next();
            return new Node.InTest(left, inTests(), t.pos());
        }
        return left;
    }

    private List<TestNode> inTests() {
        if (atOp("(")) {
            int save = p;
            int saveDepth = depth;
            try {
                Node n = additive();
                if (!atOp(",")) {
                    return List.of(new Node.ExprTest(n));
                }
            } catch (ExpressionException ignored) {
                // fall through: it is a parenthesised list of tests, not a single expression/range
            }
            p = save;
            depth = saveDepth;
            expectOp("(");
            List<TestNode> tests = testList();
            expectOp(")");
            return tests;
        }
        return List.of(test());
    }

    private Node additive() {
        Node left = multiplicative();
        while (atOp("+") || atOp("-")) {
            Token op = next();
            Node right = multiplicative();
            left = new Node.Binary(op.text().equals("+") ? BinOp.ADD : BinOp.SUB, left, right, op.pos());
        }
        return left;
    }

    private Node multiplicative() {
        Node left = power();
        while (atOp("*") || atOp("/")) {
            Token op = next();
            Node right = power();
            left = new Node.Binary(op.text().equals("*") ? BinOp.MUL : BinOp.DIV, left, right, op.pos());
        }
        return left;
    }

    private Node power() {
        Node base = unary();
        if (atOp("**")) {
            Token op = next();
            return new Node.Binary(BinOp.POW, base, power(), op.pos());
        }
        return base;
    }

    private Node unary() {
        if (atOp("-")) {
            Token op = next();
            enter();
            try {
                return new Node.Negate(unary(), op.pos());
            } finally {
                leave();
            }
        }
        return postfix();
    }

    private Node postfix() {
        Node n = primary();
        while (true) {
            if (atOp(".")) {
                Token dot = next();
                Token name = next();
                if (name.type() != Type.IDENT && name.type() != Type.QUOTED_IDENT) {
                    throw error("Expected a field name after '.'", name);
                }
                n = new Node.Path(n, name.text(), dot.pos());
            } else if (atOp("[")) {
                Token open = next();
                Node predicate = expression();
                expectOp("]");
                n = new Node.Index(n, predicate, open.pos());
            } else {
                return n;
            }
        }
    }

    private Node primary() {
        Token t = peek();
        switch (t.type()) {
            case NUMBER, STRING -> {
                next();
                return new Node.Literal(t.value());
            }
            case QUOTED_IDENT -> {
                next();
                return new Node.Name(t.text(), t.pos());
            }
            case IDENT -> {
                return identifier(t);
            }
            case OP -> {
                return operatorPrimary(t);
            }
            default -> throw error("Unexpected " + describe(t), t);
        }
    }

    private Node identifier(Token t) {
        String text = t.text();
        switch (text) {
            case "true" -> {
                next();
                return new Node.Literal(Boolean.TRUE);
            }
            case "false" -> {
                next();
                return new Node.Literal(Boolean.FALSE);
            }
            case "null" -> {
                next();
                return new Node.Literal(null);
            }
            case "if", "some", "every" -> {
                return expression();
            }
            default -> {
            }
        }
        if (RESERVED.contains(text)) {
            throw error("Unexpected keyword '" + text + "'", t);
        }
        next();
        if (atOp("(")) {
            next();
            List<Node> args = new ArrayList<>();
            if (!atOp(")")) {
                do {
                    args.add(expression());
                } while (matchOp(","));
            }
            expectOp(")");
            if (!Builtins.isKnown(text)) {
                throw error("Unknown function '" + text + "'" + Builtins.suggest(text), t);
            }
            return new Node.Call(text, args, t.pos());
        }
        return new Node.Name(text, t.pos());
    }

    private Node operatorPrimary(Token t) {
        switch (t.text()) {
            case "?" -> {
                next();
                return new Node.Input();
            }
            case "(" -> {
                next();
                Node first = expression();
                if (matchOp("..")) {
                    Node end = expression();
                    return range(first, false, end);
                }
                expectOp(")");
                return first;
            }
            case "[" -> {
                next();
                if (matchOp("]")) {
                    return new Node.ListLiteral(List.of());
                }
                Node first = expression();
                if (matchOp("..")) {
                    Node end = expression();
                    return range(first, true, end);
                }
                List<Node> items = new ArrayList<>();
                items.add(first);
                while (matchOp(",")) {
                    items.add(expression());
                }
                expectOp("]");
                return new Node.ListLiteral(items);
            }
            case "{" -> {
                next();
                List<Node.ContextEntry> entries = new ArrayList<>();
                if (!atOp("}")) {
                    do {
                        Token key = next();
                        if (key.type() != Type.IDENT && key.type() != Type.STRING
                                && key.type() != Type.QUOTED_IDENT) {
                            throw error("Expected a context key but found " + describe(key), key);
                        }
                        expectOp(":");
                        entries.add(new Node.ContextEntry(key.text(), expression()));
                    } while (matchOp(","));
                }
                expectOp("}");
                return new Node.ContextLiteral(entries);
            }
            default -> throw error("Unexpected " + describe(t), t);
        }
    }

    private Node range(Node start, boolean startIncluded, Node end) {
        Token closer = next();
        if (!closer.isOp(")") && !closer.isOp("]")) {
            throw error("Expected ')' or ']' to close the range", closer);
        }
        return new Node.RangeLiteral(start, startIncluded, end, closer.isOp("]"));
    }

    // ------------------------------------------------------------- unary tests

    private List<TestNode> testList() {
        List<TestNode> tests = new ArrayList<>();
        tests.add(test());
        while (matchOp(",")) {
            tests.add(test());
        }
        return tests;
    }

    private TestNode test() {
        Token t = peek();
        if (t.isOp("-")) {
            Token after = peekAt(1);
            if (after.type() == Type.EOF || after.isOp(",") || after.isOp(")")) {
                next();
                return new Node.AnyTest();
            }
        }
        if (t.isKeyword("not") && peekAt(1).isOp("(")) {
            next();
            next();
            List<TestNode> inner = testList();
            expectOp(")");
            return new Node.NotTest(inner);
        }
        if (t.type() == Type.OP) {
            BinOp op = switch (t.text()) {
                case "<" -> BinOp.LT;
                case "<=" -> BinOp.LE;
                case ">" -> BinOp.GT;
                case ">=" -> BinOp.GE;
                case "=" -> BinOp.EQ;
                case "!=" -> BinOp.NE;
                default -> null;
            };
            if (op != null) {
                next();
                return new Node.CompareTest(op, additive());
            }
        }
        return new Node.ExprTest(expression());
    }
}
