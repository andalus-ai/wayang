package tech.kayys.wayang.runtime.json;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Minimal JSON codec for ticket info maps (so this module needs no JSON
 * library). Supports objects, arrays, strings, numbers, booleans and null;
 * numbers are read as {@link BigDecimal}, matching the expression engine.
 */
public final class Json {

    private static final int MAX_DEPTH = 64;

    private Json() {
    }

    public static String write(Object v) {
        StringBuilder sb = new StringBuilder();
        write(v, sb, 0);
        return sb.toString();
    }

    private static void write(Object v, StringBuilder sb, int depth) {
        if (depth > MAX_DEPTH) {
            throw new IllegalArgumentException("JSON nested too deeply");
        }
        if (v == null) {
            sb.append("null");
        } else if (v instanceof String s) {
            string(s, sb);
        } else if (v instanceof Boolean b) {
            sb.append(b);
        } else if (v instanceof Number n) {
            sb.append(n instanceof BigDecimal bd ? bd.toPlainString() : n.toString());
        } else if (v instanceof Map<?, ?> m) {
            sb.append('{');
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                string(String.valueOf(e.getKey()), sb);
                sb.append(':');
                write(e.getValue(), sb, depth + 1);
            }
            sb.append('}');
        } else if (v instanceof Iterable<?> l) {
            sb.append('[');
            boolean first = true;
            for (Object o : l) {
                if (!first) {
                    sb.append(',');
                }
                first = false;
                write(o, sb, depth + 1);
            }
            sb.append(']');
        } else {
            string(String.valueOf(v), sb);
        }
    }

    private static void string(String s, StringBuilder sb) {
        sb.append('"');
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            switch (c) {
                case '"' -> sb.append("\\\"");
                case '\\' -> sb.append("\\\\");
                case '\n' -> sb.append("\\n");
                case '\r' -> sb.append("\\r");
                case '\t' -> sb.append("\\t");
                default -> {
                    if (c < 0x20) {
                        sb.append(String.format("\\u%04x", (int) c));
                    } else {
                        sb.append(c);
                    }
                }
            }
        }
        sb.append('"');
    }

    public static Object read(String json) {
        Parser p = new Parser(json);
        p.ws();
        Object v = p.value(0);
        p.ws();
        if (p.i != json.length()) {
            throw p.error("trailing characters");
        }
        return v;
    }

    private static final class Parser {
        final String s;
        int i;

        Parser(String s) {
            this.s = s;
        }

        IllegalArgumentException error(String m) {
            return new IllegalArgumentException("Invalid JSON at " + i + ": " + m);
        }

        void ws() {
            while (i < s.length() && Character.isWhitespace(s.charAt(i))) {
                i++;
            }
        }

        Object value(int depth) {
            if (depth > MAX_DEPTH) {
                throw error("nested too deeply");
            }
            if (i >= s.length()) {
                throw error("unexpected end");
            }
            char c = s.charAt(i);
            switch (c) {
                case '{': {
                    i++;
                    Map<String, Object> m = new LinkedHashMap<>();
                    ws();
                    if (peek('}')) {
                        i++;
                        return m;
                    }
                    while (true) {
                        ws();
                        if (!peek('"')) {
                            throw error("expected a key");
                        }
                        String k = str();
                        ws();
                        expect(':');
                        ws();
                        m.put(k, value(depth + 1));
                        ws();
                        if (peek(',')) {
                            i++;
                        } else {
                            expect('}');
                            return m;
                        }
                    }
                }
                case '[': {
                    i++;
                    List<Object> l = new ArrayList<>();
                    ws();
                    if (peek(']')) {
                        i++;
                        return l;
                    }
                    while (true) {
                        ws();
                        l.add(value(depth + 1));
                        ws();
                        if (peek(',')) {
                            i++;
                        } else {
                            expect(']');
                            return l;
                        }
                    }
                }
                case '"':
                    return str();
                case 't':
                    return lit("true", Boolean.TRUE);
                case 'f':
                    return lit("false", Boolean.FALSE);
                case 'n':
                    return lit("null", null);
                default:
                    return number();
            }
        }

        boolean peek(char c) {
            return i < s.length() && s.charAt(i) == c;
        }

        void expect(char c) {
            if (!peek(c)) {
                throw error("expected '" + c + "'");
            }
            i++;
        }

        Object lit(String word, Object v) {
            if (!s.startsWith(word, i)) {
                throw error("unexpected token");
            }
            i += word.length();
            return v;
        }

        Object number() {
            int st = i;
            while (i < s.length() && "+-0123456789.eE".indexOf(s.charAt(i)) >= 0) {
                i++;
            }
            if (st == i) {
                throw error("unexpected character '" + s.charAt(i) + "'");
            }
            try {
                return new BigDecimal(s.substring(st, i));
            } catch (NumberFormatException e) {
                throw error("bad number");
            }
        }

        String str() {
            expect('"');
            StringBuilder sb = new StringBuilder();
            while (true) {
                if (i >= s.length()) {
                    throw error("unterminated string");
                }
                char c = s.charAt(i++);
                if (c == '"') {
                    return sb.toString();
                }
                if (c != '\\') {
                    sb.append(c);
                    continue;
                }
                if (i >= s.length()) {
                    throw error("bad escape");
                }
                char e = s.charAt(i++);
                switch (e) {
                    case '"', '\\', '/' -> sb.append(e);
                    case 'n' -> sb.append('\n');
                    case 'r' -> sb.append('\r');
                    case 't' -> sb.append('\t');
                    case 'b' -> sb.append('\b');
                    case 'f' -> sb.append('\f');
                    case 'u' -> {
                        if (i + 4 > s.length()) {
                            throw error("bad unicode escape");
                        }
                        sb.append((char) Integer.parseInt(s.substring(i, i + 4), 16));
                        i += 4;
                    }
                    default -> throw error("bad escape \\" + e);
                }
            }
        }
    }
}
