package tech.kayys.wayang.mcp;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Validates tool arguments against the generated input schema: type, required, enum, bounds, items, unknown
 * properties. {@code pattern}, {@code oneOf}/{@code anyOf}/{@code allOf} are not evaluated (a spec-supplied regex is an
 * attack surface, and the target API validates the rest anyway).
 */
public final class SchemaCheck {

    private static final int MAX_PROBLEMS = 8;
    private static final int MAX_DEPTH = 12;

    private SchemaCheck() {
    }

    public static List<String> validate(Map<String, Object> schema, Object value) {
        List<String> out = new ArrayList<>();
        check(schema, value, "", out, 0);
        return out;
    }

    private static void check(Object schemaObj, Object v, String path, List<String> out, int depth) {
        if (out.size() >= MAX_PROBLEMS || depth > MAX_DEPTH || !(schemaObj instanceof Map<?, ?> s)) {
            return;
        }
        String at = path.isEmpty() ? "arguments" : path;
        Object type = s.get("type");
        if (v == null) {
            if (type != null && !includes(type, "null")) {
                out.add(at + " must not be null");
            }
            return;
        }
        if (type != null && !matches(type, v)) {
            out.add(at + " must be " + describe(type));
            return;
        }
        if (s.get("enum") instanceof List<?> en && !en.isEmpty() && en.stream().noneMatch(e -> same(e, v))) {
            out.add(at + " must be one of " + (en.size() > 10 ? en.subList(0, 10) + "..." : en));
        }
        if (v instanceof Number n) {
            if (s.get("minimum") instanceof Number m && n.doubleValue() < m.doubleValue()) {
                out.add(at + " must be >= " + m);
            }
            if (s.get("maximum") instanceof Number m && n.doubleValue() > m.doubleValue()) {
                out.add(at + " must be <= " + m);
            }
            if (s.get("exclusiveMinimum") instanceof Number m && n.doubleValue() <= m.doubleValue()) {
                out.add(at + " must be > " + m);
            }
            if (s.get("exclusiveMaximum") instanceof Number m && n.doubleValue() >= m.doubleValue()) {
                out.add(at + " must be < " + m);
            }
        }
        if (v instanceof String str) {
            if (s.get("minLength") instanceof Number m && str.codePointCount(0, str.length()) < m.intValue()) {
                out.add(at + " is shorter than " + m);
            }
            if (s.get("maxLength") instanceof Number m && str.codePointCount(0, str.length()) > m.intValue()) {
                out.add(at + " is longer than " + m);
            }
        }
        if (v instanceof List<?> l) {
            if (s.get("minItems") instanceof Number m && l.size() < m.intValue()) {
                out.add(at + " needs at least " + m + " items");
            }
            if (s.get("maxItems") instanceof Number m && l.size() > m.intValue()) {
                out.add(at + " allows at most " + m + " items");
            }
            if (s.get("items") != null) {
                for (int i = 0; i < l.size() && out.size() < MAX_PROBLEMS; i++) {
                    check(s.get("items"), l.get(i), path + "[" + i + "]", out, depth + 1);
                }
            }
        }
        if (v instanceof Map<?, ?> m) {
            Map<?, ?> props = s.get("properties") instanceof Map<?, ?> p ? p : Map.of();
            if (s.get("required") instanceof List<?> req) {
                for (Object r : req) {
                    if (!m.containsKey(String.valueOf(r)) || m.get(String.valueOf(r)) == null && !nullable(props.get(String.valueOf(r)))) {
                        out.add((path.isEmpty() ? "" : path + ".") + r + " is required");
                    }
                }
            }
            for (var e : m.entrySet()) {
                String k = String.valueOf(e.getKey());
                String sub = path.isEmpty() ? k : path + "." + k;
                if (props.containsKey(k)) {
                    check(props.get(k), e.getValue(), sub, out, depth + 1);
                } else if (Boolean.FALSE.equals(s.get("additionalProperties"))) {
                    out.add("unknown argument '" + sub + "'");
                } else if (s.get("additionalProperties") instanceof Map<?, ?> ap) {
                    check(ap, e.getValue(), sub, out, depth + 1);
                }
            }
        }
    }

    private static boolean nullable(Object schema) {
        return schema instanceof Map<?, ?> m && (m.get("type") == null || includes(m.get("type"), "null"));
    }

    private static boolean includes(Object type, String t) {
        return type instanceof String s ? s.equals(t) : type instanceof List<?> l && l.contains(t);
    }

    private static boolean matches(Object type, Object v) {
        if (type instanceof List<?> l) {
            return l.stream().anyMatch(t -> matches(t, v));
        }
        return switch (String.valueOf(type)) {
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "integer" -> v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue());
            case "number" -> v instanceof Number;
            case "array" -> v instanceof List;
            case "object" -> v instanceof Map;
            case "null" -> false;
            default -> true;
        };
    }

    private static String describe(Object type) {
        return type instanceof List<?> l ? String.join(" or ", l.stream().map(String::valueOf).toList()) : "a " + type;
    }

    private static boolean same(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return x.doubleValue() == y.doubleValue();
        }
        return a == null ? b == null : a.equals(b);
    }
}
