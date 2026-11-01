package tech.kayys.wayang.eip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.expression.Expression;
import tech.kayys.wayang.expression.ExpressionException;
import tech.kayys.wayang.expression.Expressions;
import tech.kayys.wayang.expression.Scope;

/**
 * Declarative data mapper: a JSON-shaped spec compiled once into a tree, evaluated against a {@link Scope}.
 *
 * <ul>
 * <li>a string is an expression: {@code "body.first + \" \" + body.last"}</li>
 * <li>an object maps each key; a dotted key sets a nested path: {@code "customer.name": "body.n"}</li>
 * <li>{@code {"$const": v}} is a literal (the only way to emit a literal string)</li>
 * <li>{@code {"$template": "Hi ${body.name}"}} interpolates expressions into text</li>
 * <li>{@code {"$if": expr, "$then": spec, "$else": spec}}</li>
 * <li>{@code {"$each": expr, "$as": "it", "$where": expr, "$map": spec}} maps a list; {@code index} is bound too</li>
 * <li>a list maps each element; numbers, booleans and null are literals</li>
 * </ul>
 * Mapping is pure: no I/O, bounded by the expression step budget and {@link Step#MAX_FANOUT}.
 */
public final class Mapper {

    private static final int MAX_DEPTH = 32;

    private sealed interface N {
        Object eval(Scope s);
    }

    private record Const(Object v) implements N {
        public Object eval(Scope s) {
            return v;
        }
    }

    private record Expr(Expression e) implements N {
        public Object eval(Scope s) {
            return e.evaluate(s);
        }
    }

    private record Template(List<Object> parts) implements N {
        public Object eval(Scope s) {
            StringBuilder b = new StringBuilder();
            for (Object p : parts) {
                if (p instanceof Expression e) {
                    Object v = e.evaluate(s);
                    b.append(v == null ? "" : tech.kayys.wayang.expression.Values.asString(v));
                } else {
                    b.append(p);
                }
            }
            return b.toString();
        }
    }

    private record If(Expression c, N then, N otherwise) implements N {
        public Object eval(Scope s) {
            return c.test(s) ? then.eval(s) : otherwise.eval(s);
        }
    }

    private record Each(Expression src, String as, Expression where, N map) implements N {
        public Object eval(Scope s) {
            Object v = src.evaluate(s);
            if (v == null) {
                return new ArrayList<>();
            }
            if (!(v instanceof List<?> l)) {
                throw new EipException("$each '" + src.source() + "' must yield a list but yielded "
                        + v.getClass().getSimpleName());
            }
            if (l.size() > Step.MAX_FANOUT) {
                throw new EipException("$each over " + l.size() + " items exceeds " + Step.MAX_FANOUT);
            }
            List<Object> out = new ArrayList<>();
            int i = 0;
            for (Object o : l) {
                Map<String, Object> vars = new LinkedHashMap<>();
                vars.put(as, o);
                vars.put("index", java.math.BigDecimal.valueOf(i++));
                Scope c = s.child(vars);
                if (where == null || where.test(c)) {
                    out.add(map.eval(c));
                }
            }
            return out;
        }
    }

    private record Obj(Map<String, N> fields) implements N {
        public Object eval(Scope s) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<String, N> e : fields.entrySet()) {
                setPath(out, e.getKey(), e.getValue().eval(s));
            }
            return out;
        }
    }

    private record Arr(List<N> items) implements N {
        public Object eval(Scope s) {
            List<Object> out = new ArrayList<>();
            items.forEach(n -> out.add(n.eval(s)));
            return out;
        }
    }

    private final N root;
    private final boolean omitNull;

    private Mapper(N root, boolean omitNull) {
        this.root = root;
        this.omitNull = omitNull;
    }

    public static Mapper compile(Object spec, boolean omitNull) {
        return new Mapper(node(spec, 0), omitNull);
    }

    public static Mapper compile(Object spec) {
        return compile(spec, false);
    }

    public Object apply(Scope scope) {
        Object v = root.eval(scope);
        return omitNull ? strip(v) : v;
    }

    // ---- helpers (also used by the map step) ----

    /** Sets {@code a.b.c} inside nested maps, creating them; conflicts (a scalar in the way) are errors. */
    @SuppressWarnings("unchecked")
    static void setPath(Map<String, Object> target, String path, Object value) {
        String[] parts = path.split("\\.", -1);
        Map<String, Object> cur = target;
        for (int i = 0; i < parts.length - 1; i++) {
            if (parts[i].isEmpty()) {
                throw new EipException("Bad mapping key '" + path + "'");
            }
            Object next = cur.get(parts[i]);
            if (next == null) {
                next = new LinkedHashMap<String, Object>();
                cur.put(parts[i], next);
            } else if (!(next instanceof Map)) {
                throw new EipException("Mapping key '" + path + "' collides with a non-object at '" + parts[i] + "'");
            }
            cur = (Map<String, Object>) next;
        }
        String last = parts[parts.length - 1];
        if (last.isEmpty()) {
            throw new EipException("Bad mapping key '" + path + "'");
        }
        Object prev = cur.get(last);
        if (prev instanceof Map && value instanceof Map) {
            cur.put(last, deepMerge(prev, value));
        } else {
            cur.put(last, value);
        }
    }

    @SuppressWarnings("unchecked")
    static Object deepMerge(Object base, Object over) {
        if (base instanceof Map<?, ?> b && over instanceof Map<?, ?> o) {
            Map<String, Object> out = new LinkedHashMap<>();
            b.forEach((k, v) -> out.put(String.valueOf(k), v));
            o.forEach((k, v) -> {
                String key = String.valueOf(k);
                out.put(key, out.containsKey(key) ? deepMerge(out.get(key), v) : v);
            });
            return out;
        }
        return over;
    }

    /** Removes {@code a.b.c} from nested maps (copying along the way); absent paths are ignored. */
    @SuppressWarnings("unchecked")
    static Object removePath(Object body, String path) {
        if (!(body instanceof Map<?, ?> m)) {
            return body;
        }
        String[] parts = path.split("\\.", -1);
        Map<String, Object> copy = new LinkedHashMap<>();
        m.forEach((k, v) -> copy.put(String.valueOf(k), v));
        Map<String, Object> cur = copy;
        for (int i = 0; i < parts.length - 1; i++) {
            Object next = cur.get(parts[i]);
            if (!(next instanceof Map<?, ?> nm)) {
                return copy;
            }
            Map<String, Object> nc = new LinkedHashMap<>();
            nm.forEach((k, v) -> nc.put(String.valueOf(k), v));
            cur.put(parts[i], nc);
            cur = nc;
        }
        cur.remove(parts[parts.length - 1]);
        return copy;
    }

    private static Object strip(Object v) {
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, x) -> {
                if (x != null) {
                    out.put(String.valueOf(k), strip(x));
                }
            });
            return out;
        }
        if (v instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(x -> out.add(strip(x)));
            return out;
        }
        return v;
    }

    // ---- compile ----

    private static Expression parse(String what, Object src) {
        if (!(src instanceof String s)) {
            throw new EipException(what + " must be an expression string");
        }
        try {
            return Expressions.parse(s);
        } catch (ExpressionException e) {
            throw new EipException(what + " '" + s + "': " + e.getMessage(), e);
        }
    }

    private static N node(Object spec, int depth) {
        if (depth > MAX_DEPTH) {
            throw new EipException("Mapping nested deeper than " + MAX_DEPTH);
        }
        if (spec instanceof String s) {
            return new Expr(parse("mapping", s));
        }
        if (spec instanceof List<?> l) {
            List<N> items = new ArrayList<>();
            l.forEach(o -> items.add(node(o, depth + 1)));
            return new Arr(items);
        }
        if (spec instanceof Map<?, ?> m) {
            boolean directive = m.keySet().stream().anyMatch(k -> String.valueOf(k).startsWith("$"));
            if (directive) {
                return directive(m, depth);
            }
            Map<String, N> f = new LinkedHashMap<>();
            m.forEach((k, v) -> f.put(String.valueOf(k), node(v, depth + 1)));
            return new Obj(f);
        }
        return new Const(spec == null || spec instanceof Boolean ? spec
                : spec instanceof Number n ? new java.math.BigDecimal(n.toString()) : spec);
    }

    private static N directive(Map<?, ?> m, int depth) {
        java.util.Set<String> keys = new java.util.LinkedHashSet<>();
        m.keySet().forEach(k -> keys.add(String.valueOf(k)));
        if (keys.contains("$const")) {
            only(keys, "$const");
            return new Const(m.get("$const"));
        }
        if (keys.contains("$template")) {
            only(keys, "$template");
            return template(String.valueOf(m.get("$template")));
        }
        if (keys.contains("$if")) {
            only(keys, "$if", "$then", "$else");
            Expression c = parse("$if", m.get("$if"));
            N then = m.containsKey("$then") ? node(m.get("$then"), depth + 1) : new Const(null);
            N other = m.containsKey("$else") ? node(m.get("$else"), depth + 1) : new Const(null);
            return new If(c, then, other);
        }
        if (keys.contains("$each")) {
            only(keys, "$each", "$as", "$where", "$map");
            String as = m.get("$as") == null ? "item" : String.valueOf(m.get("$as"));
            if (!as.matches("[A-Za-z_][A-Za-z0-9_]*")) {
                throw new EipException("$as must be an identifier but was '" + as + "'");
            }
            Expression where = m.get("$where") == null ? null : parse("$where", m.get("$where"));
            N map = m.containsKey("$map") ? node(m.get("$map"), depth + 1) : new Expr(Expressions.parse(as));
            return new Each(parse("$each", m.get("$each")), as, where, map);
        }
        throw new EipException("Unknown mapping directive in " + keys);
    }

    private static void only(java.util.Set<String> have, String... allowed) {
        java.util.Set<String> ok = java.util.Set.of(allowed);
        for (String k : have) {
            if (!ok.contains(k)) {
                throw new EipException("Mapping key '" + k + "' is not allowed here (allowed: " + ok + ")");
            }
        }
    }

    /** {@code ${expr}} placeholders; braces and quotes inside the expression are balanced; {@code $${} is a literal. */
    static N template(String t) {
        List<Object> parts = new ArrayList<>();
        StringBuilder lit = new StringBuilder();
        int i = 0;
        while (i < t.length()) {
            if (t.startsWith("$${", i)) {
                lit.append("${");
                i += 3;
            } else if (t.startsWith("${", i)) {
                int j = i + 2;
                int depth = 1;
                char q = 0;
                while (j < t.length() && depth > 0) {
                    char c = t.charAt(j);
                    if (q != 0) {
                        if (c == '\\') {
                            j++;
                        } else if (c == q) {
                            q = 0;
                        }
                    } else if (c == '"' || c == '\'') {
                        q = c;
                    } else if (c == '{') {
                        depth++;
                    } else if (c == '}') {
                        depth--;
                    }
                    j++;
                }
                if (depth != 0) {
                    throw new EipException("Unterminated ${ in template '" + t + "'");
                }
                if (lit.length() > 0) {
                    parts.add(lit.toString());
                    lit.setLength(0);
                }
                parts.add(parse("template", t.substring(i + 2, j - 1)));
                i = j;
            } else {
                lit.append(t.charAt(i++));
            }
        }
        if (lit.length() > 0) {
            parts.add(lit.toString());
        }
        return new Template(parts);
    }
}
