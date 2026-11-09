package tech.kayys.wayang.api.definition;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * A deliberately small JSON-Schema validator for node configuration forms.
 * Supports {@code type} (string, number, integer, boolean, object, array, null,
 * or a list of them), {@code required}, {@code properties},
 * {@code additionalProperties: false}, {@code enum}, {@code const},
 * {@code minimum}/{@code maximum}, {@code minLength}/{@code maxLength},
 * {@code pattern}, {@code items}, {@code minItems}/{@code maxItems},
 * {@code oneOf}/{@code anyOf}. Everything else (including {@code $ref} and
 * {@code format}) is ignored, so it is only as strict as the schemas written for
 * it; patterns come from trusted catalog schemas, never from user documents.
 */
public final class JsonSchemaLite {

    private static final int MAX_DEPTH = 32;

    private JsonSchemaLite() {
    }

    /** @return human-readable problems, empty when valid */
    public static List<String> validate(Map<String, Object> schema, Object value) {
        List<String> out = new ArrayList<>();
        check(schema, value, "$", out, 0);
        return out;
    }

    @SuppressWarnings("unchecked")
    private static void check(Map<String, Object> s, Object v, String path, List<String> out, int depth) {
        if (depth > MAX_DEPTH) {
            out.add(path + ": nested too deeply");
            return;
        }
        Object type = s.get("type");
        if (type != null && !typeMatches(type, v)) {
            out.add(path + ": expected " + type + " but got " + describe(v));
            return;
        }
        if (s.get("enum") instanceof List<?> e && !e.contains(v) && !numericEnumMatch(e, v)) {
            out.add(path + ": must be one of " + e);
        }
        if (s.containsKey("const") && !equalsLoose(s.get("const"), v)) {
            out.add(path + ": must equal " + s.get("const"));
        }
        if (v instanceof Number n) {
            double d = n.doubleValue();
            if (s.get("minimum") instanceof Number min && d < min.doubleValue()) {
                out.add(path + ": must be >= " + min);
            }
            if (s.get("maximum") instanceof Number max && d > max.doubleValue()) {
                out.add(path + ": must be <= " + max);
            }
        }
        if (v instanceof String str) {
            if (s.get("minLength") instanceof Number n && str.length() < n.intValue()) {
                out.add(path + ": must have at least " + n + " characters");
            }
            if (s.get("maxLength") instanceof Number n && str.length() > n.intValue()) {
                out.add(path + ": must have at most " + n + " characters");
            }
            if (s.get("pattern") instanceof String p && !Pattern.compile(p).matcher(str).find()) {
                out.add(path + ": must match " + p);
            }
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> obj = (Map<String, Object>) m;
            if (s.get("required") instanceof List<?> req) {
                for (Object r : req) {
                    if (!obj.containsKey(String.valueOf(r)) || obj.get(String.valueOf(r)) == null) {
                        out.add(path + ": '" + r + "' is required");
                    }
                }
            }
            Map<String, Object> props = s.get("properties") instanceof Map<?, ?> p ? (Map<String, Object>) p : Map.of();
            for (Map.Entry<String, Object> e : obj.entrySet()) {
                Object sub = props.get(e.getKey());
                if (sub instanceof Map<?, ?> sm) {
                    check((Map<String, Object>) sm, e.getValue(), path + "." + e.getKey(), out, depth + 1);
                } else if (Boolean.FALSE.equals(s.get("additionalProperties"))) {
                    out.add(path + ": unknown property '" + e.getKey() + "'");
                }
            }
        }
        if (v instanceof List<?> l) {
            if (s.get("minItems") instanceof Number n && l.size() < n.intValue()) {
                out.add(path + ": must have at least " + n + " items");
            }
            if (s.get("maxItems") instanceof Number n && l.size() > n.intValue()) {
                out.add(path + ": must have at most " + n + " items");
            }
            if (s.get("items") instanceof Map<?, ?> im) {
                for (int i = 0; i < l.size(); i++) {
                    check((Map<String, Object>) im, l.get(i), path + "[" + i + "]", out, depth + 1);
                }
            }
        }
        if (s.get("anyOf") instanceof List<?> any && !matchesAny(any, v, depth)) {
            out.add(path + ": does not match any allowed form");
        }
        if (s.get("oneOf") instanceof List<?> one && countMatches(one, v, depth) != 1) {
            out.add(path + ": must match exactly one allowed form");
        }
    }

    @SuppressWarnings("unchecked")
    private static boolean matchesAny(List<?> alts, Object v, int depth) {
        return countMatches(alts, v, depth) > 0;
    }

    @SuppressWarnings("unchecked")
    private static int countMatches(List<?> alts, Object v, int depth) {
        int n = 0;
        for (Object a : alts) {
            List<String> errs = new ArrayList<>();
            check((Map<String, Object>) a, v, "$", errs, depth + 1);
            if (errs.isEmpty()) {
                n++;
            }
        }
        return n;
    }

    private static boolean typeMatches(Object type, Object v) {
        if (type instanceof List<?> l) {
            return l.stream().anyMatch(t -> typeMatches(t, v));
        }
        return switch (String.valueOf(type)) {
            case "string" -> v instanceof String;
            case "boolean" -> v instanceof Boolean;
            case "number" -> v instanceof Number;
            case "integer" -> v instanceof Number n && (n instanceof Integer || n instanceof Long || n instanceof Short
                    || n.doubleValue() == Math.rint(n.doubleValue()));
            case "object" -> v instanceof Map;
            case "array" -> v instanceof List;
            case "null" -> v == null;
            default -> true;
        };
    }

    private static boolean numericEnumMatch(List<?> e, Object v) {
        return v instanceof Number && e.stream().anyMatch(x -> equalsLoose(x, v));
    }

    private static boolean equalsLoose(Object a, Object b) {
        if (a instanceof Number x && b instanceof Number y) {
            return Double.compare(x.doubleValue(), y.doubleValue()) == 0;
        }
        return java.util.Objects.equals(a, b);
    }

    private static String describe(Object v) {
        return v == null ? "null" : v instanceof Map ? "object" : v instanceof List ? "array"
                : v instanceof String ? "string" : v instanceof Boolean ? "boolean" : v instanceof Number ? "number" : v.getClass().getSimpleName();
    }
}
