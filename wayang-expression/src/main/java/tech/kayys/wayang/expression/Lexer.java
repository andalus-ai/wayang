package tech.kayys.wayang.expression;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Tokenizer for the Wayang expression language. */
final class Lexer {

    enum Type {
        NUMBER, STRING, IDENT, QUOTED_IDENT, OP, EOF
    }

    record Token(Type type, String text, int pos, Object value) {
        boolean isOp(String op) {
            return type == Type.OP && text.equals(op);
        }

        boolean isKeyword(String kw) {
            return type == Type.IDENT && text.equals(kw);
        }
    }

    private final String src;
    private int i;

    private Lexer(String src) {
        this.src = src;
    }

    static List<Token> tokenize(String src) {
        return new Lexer(src).run();
    }

    private List<Token> run() {
        List<Token> out = new ArrayList<>();
        while (true) {
            skipWhitespace();
            if (i >= src.length()) {
                out.add(new Token(Type.EOF, "", i, null));
                return out;
            }
            char c = src.charAt(i);
            if (Character.isDigit(c)) {
                out.add(number());
            } else if (c == '"' || c == '\'') {
                out.add(string(c));
            } else if (c == '`') {
                out.add(quotedIdent());
            } else if (isIdentStart(c)) {
                out.add(identifier());
            } else {
                out.add(operator());
            }
        }
    }

    private void skipWhitespace() {
        while (i < src.length()) {
            char c = src.charAt(i);
            if (Character.isWhitespace(c)) {
                i++;
            } else if (c == '/' && i + 1 < src.length() && src.charAt(i + 1) == '/') {
                while (i < src.length() && src.charAt(i) != '\n') {
                    i++;
                }
            } else {
                break;
            }
        }
    }

    private static boolean isIdentStart(char c) {
        return Character.isLetter(c) || c == '_' || c == '$';
    }

    private static boolean isIdentPart(char c) {
        return Character.isLetterOrDigit(c) || c == '_' || c == '$';
    }

    private Token number() {
        int start = i;
        while (i < src.length() && Character.isDigit(src.charAt(i))) {
            i++;
        }
        // a '.' is part of the number only when followed by a digit ("1..5" is a range)
        if (i + 1 < src.length() && src.charAt(i) == '.' && Character.isDigit(src.charAt(i + 1))) {
            i++;
            while (i < src.length() && Character.isDigit(src.charAt(i))) {
                i++;
            }
        }
        if (i < src.length() && (src.charAt(i) == 'e' || src.charAt(i) == 'E')) {
            int save = i;
            i++;
            if (i < src.length() && (src.charAt(i) == '+' || src.charAt(i) == '-')) {
                i++;
            }
            if (i < src.length() && Character.isDigit(src.charAt(i))) {
                while (i < src.length() && Character.isDigit(src.charAt(i))) {
                    i++;
                }
            } else {
                i = save;
            }
        }
        String text = src.substring(start, i);
        return new Token(Type.NUMBER, text, start, new BigDecimal(text));
    }

    private Token string(char quote) {
        int start = i;
        i++;
        StringBuilder sb = new StringBuilder();
        while (true) {
            if (i >= src.length()) {
                throw new ExpressionException("Unterminated string literal", src, start);
            }
            char c = src.charAt(i++);
            if (c == quote) {
                break;
            }
            if (c == '\\') {
                if (i >= src.length()) {
                    throw new ExpressionException("Unterminated escape sequence", src, i - 1);
                }
                char e = src.charAt(i++);
                switch (e) {
                    case 'n' -> sb.append('\n');
                    case 't' -> sb.append('\t');
                    case 'r' -> sb.append('\r');
                    case '\\' -> sb.append('\\');
                    case '"' -> sb.append('"');
                    case '\'' -> sb.append('\'');
                    case 'u' -> {
                        if (i + 4 > src.length()) {
                            throw new ExpressionException("Invalid unicode escape", src, i - 2);
                        }
                        try {
                            sb.append((char) Integer.parseInt(src.substring(i, i + 4), 16));
                        } catch (NumberFormatException ex) {
                            throw new ExpressionException("Invalid unicode escape", src, i - 2);
                        }
                        i += 4;
                    }
                    default -> throw new ExpressionException("Unknown escape \\" + e, src, i - 2);
                }
            } else {
                sb.append(c);
            }
        }
        return new Token(Type.STRING, sb.toString(), start, sb.toString());
    }

    private Token quotedIdent() {
        int start = i;
        i++;
        int end = src.indexOf('`', i);
        if (end < 0) {
            throw new ExpressionException("Unterminated quoted name", src, start);
        }
        String name = src.substring(i, end);
        i = end + 1;
        return new Token(Type.QUOTED_IDENT, name, start, name);
    }

    private Token identifier() {
        int start = i;
        // multi-word built-in names such as "string length(" or "date and time("
        for (String name : Builtins.MULTI_WORD_NAMES) {
            if (src.regionMatches(start, name, 0, name.length())) {
                int after = start + name.length();
                int p = after;
                while (p < src.length() && src.charAt(p) == ' ') {
                    p++;
                }
                boolean boundary = after >= src.length() || !isIdentPart(src.charAt(after));
                if (boundary && p < src.length() && src.charAt(p) == '(') {
                    i = after;
                    return new Token(Type.IDENT, name, start, name);
                }
            }
        }
        while (i < src.length() && isIdentPart(src.charAt(i))) {
            i++;
        }
        String text = src.substring(start, i);
        return new Token(Type.IDENT, text, start, text);
    }

    private Token operator() {
        int start = i;
        char c = src.charAt(i);
        String two = i + 1 < src.length() ? src.substring(i, i + 2) : "";
        switch (two) {
            case "**", "<=", ">=", "!=", "..", "==", "&&", "||" -> {
                i += 2;
                return new Token(Type.OP, switch (two) {
                    case "==" -> "=";
                    case "&&" -> "and";
                    case "||" -> "or";
                    default -> two;
                }, start, null);
            }
            default -> {
            }
        }
        if ("+-*/()[]{},.:=<>?".indexOf(c) >= 0) {
            i++;
            return new Token(Type.OP, String.valueOf(c), start, null);
        }
        throw new ExpressionException("Unexpected character '" + c + "'", src, start);
    }
}
