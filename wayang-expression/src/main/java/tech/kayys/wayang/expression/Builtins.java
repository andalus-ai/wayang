package tech.kayys.wayang.expression;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Built-in function library. Function names follow the DMN FEEL spelling
 * (including multi-word names such as {@code string length}). There is
 * deliberately no regex, reflection, I/O or randomness: every function is
 * deterministic and bounded, which is a prerequisite for replayable workflows.
 */
final class Builtins {

    /** Built-ins whose name contains spaces; longest first so that "date and time" wins over "date". */
    static final List<String> MULTI_WORD_NAMES = List.of(
            "substring before", "substring after", "string length", "string join", "upper case", "lower case",
            "starts with", "ends with", "list contains", "distinct values", "index of", "date and time",
            "round half up", "round half down", "round up", "round down", "get value", "get entries",
            "context put", "context merge", "is defined");

    /** Every function name {@code dispatch} implements (a test keeps this in step with the switch); unknown names are rejected at parse time. */
    static final java.util.Set<String> NAMES = java.util.Set.of(
            "abs",
            "all",
            "any",
            "append",
            "ceiling",
            "coalesce",
            "concatenate",
            "contains",
            "context merge",
            "context put",
            "count",
            "date",
            "date and time",
            "decimal",
            "distinct values",
            "duration",
            "ends with",
            "even",
            "flatten",
            "floor",
            "get entries",
            "get value",
            "index of",
            "is defined",
            "list contains",
            "lower case",
            "max",
            "mean",
            "min",
            "modulo",
            "not",
            "now",
            "number",
            "odd",
            "product",
            "remove",
            "replace",
            "reverse",
            "round",
            "round down",
            "round half down",
            "round half up",
            "round up",
            "sort",
            "split",
            "sqrt",
            "starts with",
            "string",
            "string join",
            "string length",
            "sublist",
            "substring",
            "substring after",
            "substring before",
            "sum",
            "time",
            "today",
            "trim",
            "union",
            "upper case");

    static boolean isKnown(String name) {
        return NAMES.contains(name);
    }

    static String suggest(String name) {
        String n = name.replace(" ", "").toLowerCase(java.util.Locale.ROOT);
        java.util.List<String> hits = new java.util.ArrayList<>();
        for (String k : new java.util.TreeSet<>(NAMES)) {
            String c = k.replace(" ", "");
            if (c.startsWith(n) || n.startsWith(c) || c.contains(n)) {
                hits.add(k);
            }
        }
        return hits.isEmpty() ? "" : " (did you mean " + String.join(", ", hits.subList(0, Math.min(3, hits.size()))) + "?)";
    }

    private static final int MAX_STRING = 1_000_000;
    private static final int MAX_LIST = 100_000;

    private Builtins() {
    }

    static Object call(String name, List<Object> a, Scope scope, Interpreter it, int pos) {
        try {
            return dispatch(name, a, scope, it, pos);
        } catch (ArgumentException e) {
            return it.fail(scope, "Function " + name + "(): " + e.getMessage(), pos);
        }
    }

    /** Signals a bad argument; converted into a (possibly null) evaluation failure. */
    private static final class ArgumentException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        ArgumentException(String m) {
            super(m, null, false, false);
        }
    }

    private static Object dispatch(String name, List<Object> a, Scope scope, Interpreter it, int pos) {
        switch (name) {
            // ------------------------------------------------------------ numbers
            case "abs":
                return n(a, 0, name) == null ? null : n(a, 0, name).abs();
            case "ceiling":
                return arity(a, 1, name) && n(a, 0, name) != null
                        ? n(a, 0, name).setScale(0, RoundingMode.CEILING) : null;
            case "floor":
                return arity(a, 1, name) && n(a, 0, name) != null
                        ? n(a, 0, name).setScale(0, RoundingMode.FLOOR) : null;
            case "round":
            case "round half up":
                return round(a, name, RoundingMode.HALF_UP);
            case "round half down":
                return round(a, name, RoundingMode.HALF_DOWN);
            case "round up":
                return round(a, name, RoundingMode.UP);
            case "round down":
                return round(a, name, RoundingMode.DOWN);
            case "decimal":
                return round(a, name, RoundingMode.HALF_EVEN);
            case "sqrt": {
                BigDecimal x = n(a, 0, name);
                if (x == null) {
                    return null;
                }
                if (x.signum() < 0) {
                    throw new ArgumentException("cannot take the square root of a negative number");
                }
                return x.sqrt(MathContext.DECIMAL128);
            }
            case "modulo": {
                arity(a, 2, name);
                BigDecimal x = n(a, 0, name);
                BigDecimal y = n(a, 1, name);
                if (x == null || y == null) {
                    return null;
                }
                if (y.signum() == 0) {
                    throw new ArgumentException("modulo by zero");
                }
                return x.subtract(y.multiply(x.divide(y, 0, RoundingMode.FLOOR)));
            }
            case "even":
                return parity(a, name, true);
            case "odd":
                return parity(a, name, false);
            case "min":
                return extreme(flat(a), true);
            case "max":
                return extreme(flat(a), false);
            case "sum": {
                List<Object> l = flat(a);
                BigDecimal s = BigDecimal.ZERO;
                for (Object o : l) {
                    s = s.add(num(o, name));
                }
                return s;
            }
            case "product": {
                List<Object> l = flat(a);
                BigDecimal s = BigDecimal.ONE;
                for (Object o : l) {
                    s = s.multiply(num(o, name));
                }
                return s;
            }
            case "mean": {
                List<Object> l = flat(a);
                if (l.isEmpty()) {
                    return null;
                }
                BigDecimal s = BigDecimal.ZERO;
                for (Object o : l) {
                    s = s.add(num(o, name));
                }
                return s.divide(BigDecimal.valueOf(l.size()), MathContext.DECIMAL128);
            }
            case "count":
                return BigDecimal.valueOf(flat(a).size());

            // ------------------------------------------------------------ strings
            case "string length": {
                String s = s(a, 0, name);
                return s == null ? null : BigDecimal.valueOf(s.length());
            }
            case "upper case": {
                String s = s(a, 0, name);
                return s == null ? null : s.toUpperCase(java.util.Locale.ROOT);
            }
            case "lower case": {
                String s = s(a, 0, name);
                return s == null ? null : s.toLowerCase(java.util.Locale.ROOT);
            }
            case "trim": {
                String s = s(a, 0, name);
                return s == null ? null : s.strip();
            }
            case "contains": {
                arity(a, 2, name);
                String s = s(a, 0, name);
                String t = s(a, 1, name);
                return s == null || t == null ? null : s.contains(t);
            }
            case "starts with": {
                arity(a, 2, name);
                String s = s(a, 0, name);
                String t = s(a, 1, name);
                return s == null || t == null ? null : s.startsWith(t);
            }
            case "ends with": {
                arity(a, 2, name);
                String s = s(a, 0, name);
                String t = s(a, 1, name);
                return s == null || t == null ? null : s.endsWith(t);
            }
            case "substring": {
                String s = s(a, 0, name);
                BigDecimal start = n(a, 1, name);
                if (s == null || start == null) {
                    return null;
                }
                int st = start.intValue();
                int from = st > 0 ? st - 1 : s.length() + st;
                if (from < 0 || from > s.length()) {
                    return null;
                }
                if (a.size() > 2 && a.get(2) != null) {
                    int len = n(a, 2, name).intValue();
                    if (len < 0) {
                        return null;
                    }
                    return s.substring(from, Math.min(s.length(), from + len));
                }
                return s.substring(from);
            }
            case "substring before": {
                arity(a, 2, name);
                String s = s(a, 0, name);
                String t = s(a, 1, name);
                if (s == null || t == null) {
                    return null;
                }
                int i = s.indexOf(t);
                return i < 0 ? "" : s.substring(0, i);
            }
            case "substring after": {
                arity(a, 2, name);
                String s = s(a, 0, name);
                String t = s(a, 1, name);
                if (s == null || t == null) {
                    return null;
                }
                int i = s.indexOf(t);
                return i < 0 ? "" : s.substring(i + t.length());
            }
            case "replace": {
                arity(a, 3, name);
                String s = s(a, 0, name);
                String from = s(a, 1, name);
                String to = s(a, 2, name);
                if (s == null || from == null || to == null) {
                    return null;
                }
                if (from.isEmpty()) {
                    throw new ArgumentException("the text to replace must not be empty");
                }
                String r = s.replace(from, to);
                if (r.length() > MAX_STRING) {
                    throw new ArgumentException("result is too large");
                }
                return r;
            }
            case "split": {
                arity(a, 2, name);
                String s = s(a, 0, name);
                String d = s(a, 1, name);
                if (s == null || d == null) {
                    return null;
                }
                if (d.isEmpty()) {
                    throw new ArgumentException("the delimiter must not be empty");
                }
                List<Object> out = new ArrayList<>();
                int from = 0;
                while (true) {
                    int i = s.indexOf(d, from);
                    if (i < 0) {
                        out.add(s.substring(from));
                        return out;
                    }
                    out.add(s.substring(from, i));
                    from = i + d.length();
                    if (out.size() > MAX_LIST) {
                        throw new ArgumentException("result is too large");
                    }
                }
            }
            case "string join": {
                List<?> l = l(a, 0, name);
                String d = a.size() > 1 ? s(a, 1, name) : "";
                if (l == null) {
                    return null;
                }
                StringBuilder sb = new StringBuilder();
                boolean first = true;
                for (Object o : l) {
                    if (o == null) {
                        continue;
                    }
                    if (!first && d != null) {
                        sb.append(d);
                    }
                    first = false;
                    sb.append(Values.asString(o));
                    if (sb.length() > MAX_STRING) {
                        throw new ArgumentException("result is too large");
                    }
                }
                return sb.toString();
            }
            case "string":
                arity(a, 1, name);
                return a.get(0) == null ? null : Values.asString(a.get(0));
            case "number": {
                arity(a, 1, name);
                Object v = a.get(0);
                if (v == null) {
                    return null;
                }
                if (v instanceof BigDecimal) {
                    return v;
                }
                if (v instanceof String s) {
                    try {
                        return new BigDecimal(s.strip());
                    } catch (NumberFormatException e) {
                        return null;
                    }
                }
                throw new ArgumentException("cannot convert " + Values.typeName(v) + " to a number");
            }

            // -------------------------------------------------------------- lists
            case "list contains": {
                arity(a, 2, name);
                List<?> l = l(a, 0, name);
                if (l == null) {
                    return null;
                }
                for (Object o : l) {
                    if (Values.valueEquals(o, a.get(1))) {
                        return true;
                    }
                }
                return false;
            }
            case "append": {
                List<?> l = l(a, 0, name);
                if (l == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>(l);
                out.addAll(a.subList(1, a.size()));
                return bounded(out);
            }
            case "concatenate": {
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < a.size(); i++) {
                    List<?> l = l(a, i, name);
                    if (l != null) {
                        out.addAll(l);
                    }
                }
                return bounded(out);
            }
            case "union": {
                Set<Object> seen = new LinkedHashSet<>();
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < a.size(); i++) {
                    List<?> l = l(a, i, name);
                    if (l == null) {
                        continue;
                    }
                    for (Object o : l) {
                        if (containsValue(out, o)) {
                            continue;
                        }
                        out.add(o);
                    }
                }
                return bounded(out);
            }
            case "reverse": {
                List<?> l = l(a, 0, name);
                if (l == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>(l);
                java.util.Collections.reverse(out);
                return out;
            }
            case "distinct values": {
                List<?> l = l(a, 0, name);
                if (l == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>();
                for (Object o : l) {
                    if (!containsValue(out, o)) {
                        out.add(o);
                    }
                }
                return out;
            }
            case "index of": {
                arity(a, 2, name);
                List<?> l = l(a, 0, name);
                if (l == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>();
                for (int i = 0; i < l.size(); i++) {
                    if (Values.valueEquals(l.get(i), a.get(1))) {
                        out.add(BigDecimal.valueOf(i + 1));
                    }
                }
                return out;
            }
            case "sort": {
                List<?> l = l(a, 0, name);
                if (l == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>(l);
                for (Object o : out) {
                    if (o == null || Values.compare(o, out.get(0)) == null) {
                        throw new ArgumentException("list elements must be non-null and of the same type");
                    }
                }
                out.sort(Comparator.comparing(o -> o, (x, y) -> Values.compare(x, y)));
                return out;
            }
            case "flatten": {
                List<?> l = l(a, 0, name);
                if (l == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>();
                flattenInto(l, out, 0);
                return bounded(out);
            }
            case "sublist": {
                List<?> l = l(a, 0, name);
                BigDecimal st = n(a, 1, name);
                if (l == null || st == null) {
                    return null;
                }
                int s = st.intValue();
                int from = s > 0 ? s - 1 : l.size() + s;
                if (from < 0 || from > l.size()) {
                    return null;
                }
                int to = l.size();
                if (a.size() > 2 && a.get(2) != null) {
                    to = Math.min(to, from + Math.max(0, n(a, 2, name).intValue()));
                }
                return new ArrayList<>(l.subList(from, to));
            }
            case "remove": {
                arity(a, 2, name);
                List<?> l = l(a, 0, name);
                BigDecimal pos1 = n(a, 1, name);
                if (l == null || pos1 == null) {
                    return null;
                }
                int p = pos1.intValue();
                int idx = p > 0 ? p - 1 : l.size() + p;
                if (idx < 0 || idx >= l.size()) {
                    return null;
                }
                List<Object> out = new ArrayList<>(l);
                out.remove(idx);
                return out;
            }

            // ------------------------------------------------------------ booleans
            case "not": {
                arity(a, 1, name);
                Object v = a.get(0);
                if (v == null) {
                    return null;
                }
                if (v instanceof Boolean b) {
                    return !b;
                }
                throw new ArgumentException("expected a boolean");
            }
            case "all":
            case "any": {
                boolean all = name.equals("all");
                boolean sawNull = false;
                for (Object o : flat(a)) {
                    if (o == null) {
                        sawNull = true;
                    } else if (o instanceof Boolean b) {
                        if (all && !b) {
                            return false;
                        }
                        if (!all && b) {
                            return true;
                        }
                    } else {
                        throw new ArgumentException("expected booleans");
                    }
                }
                return sawNull ? null : all;
            }
            case "coalesce":
                for (Object o : a) {
                    if (o != null) {
                        return o;
                    }
                }
                return null;
            case "is defined":
                arity(a, 1, name);
                return a.get(0) != null;

            // ------------------------------------------------------------ contexts
            case "get value": {
                arity(a, 2, name);
                Map<?, ?> m = m(a, 0, name);
                String k = s(a, 1, name);
                return m == null || k == null ? null : m.get(k);
            }
            case "get entries": {
                Map<?, ?> m = m(a, 0, name);
                if (m == null) {
                    return null;
                }
                List<Object> out = new ArrayList<>();
                for (Map.Entry<?, ?> e : m.entrySet()) {
                    Map<String, Object> entry = new LinkedHashMap<>();
                    entry.put("key", String.valueOf(e.getKey()));
                    entry.put("value", e.getValue());
                    out.add(entry);
                }
                return out;
            }
            case "context put": {
                arity(a, 3, name);
                Map<?, ?> m = m(a, 0, name);
                String k = s(a, 1, name);
                if (m == null || k == null) {
                    return null;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                m.forEach((kk, vv) -> out.put(String.valueOf(kk), vv));
                out.put(k, a.get(2));
                return out;
            }
            case "context merge": {
                List<?> l = l(a, 0, name);
                if (l == null) {
                    return null;
                }
                Map<String, Object> out = new LinkedHashMap<>();
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m) {
                        m.forEach((kk, vv) -> out.put(String.valueOf(kk), vv));
                    } else {
                        throw new ArgumentException("expected a list of contexts");
                    }
                }
                return out;
            }

            // --------------------------------------------------------- date / time
            case "now":
                return OffsetDateTime.now(scope.clock());
            case "today":
                return LocalDate.now(scope.clock());
            case "date": {
                if (a.size() == 3) {
                    return LocalDate.of(n(a, 0, name).intValueExact(), n(a, 1, name).intValueExact(),
                            n(a, 2, name).intValueExact());
                }
                arity(a, 1, name);
                Object v = a.get(0);
                if (v == null) {
                    return null;
                }
                if (v instanceof LocalDate) {
                    return v;
                }
                if (v instanceof OffsetDateTime odt) {
                    return odt.toLocalDate();
                }
                return LocalDate.parse(s(a, 0, name));
            }
            case "date and time": {
                arity(a, 1, name);
                Object v = a.get(0);
                if (v == null) {
                    return null;
                }
                if (v instanceof OffsetDateTime) {
                    return v;
                }
                String s = s(a, 0, name);
                try {
                    return OffsetDateTime.parse(s);
                } catch (DateTimeParseException e) {
                    return java.time.LocalDateTime.parse(s).atOffset(java.time.ZoneOffset.UTC);
                }
            }
            case "time": {
                arity(a, 1, name);
                Object v = a.get(0);
                if (v == null) {
                    return null;
                }
                if (v instanceof LocalTime) {
                    return v;
                }
                return LocalTime.parse(s(a, 0, name));
            }
            case "duration": {
                arity(a, 1, name);
                Object v = a.get(0);
                if (v == null) {
                    return null;
                }
                if (v instanceof Duration || v instanceof Period) {
                    return v;
                }
                String s = s(a, 0, name);
                String u = s.toUpperCase(java.util.Locale.ROOT);
                if (u.contains("T") || u.matches("^-?P\\d+D$")) {
                    return Duration.parse(s);
                }
                return Period.parse(s);
            }
            default:
                return it.fail(scope, "Unknown function '" + name + "'", pos);
        }
    }

    // --------------------------------------------------------------- helpers

    private static boolean arity(List<Object> a, int expected, String name) {
        if (a.size() != expected) {
            throw new ArgumentException("expects " + expected + " argument(s) but got " + a.size());
        }
        return true;
    }

    private static BigDecimal num(Object o, String name) {
        if (o instanceof BigDecimal b) {
            return b;
        }
        throw new ArgumentException("expected numbers but found " + Values.typeName(o));
    }

    private static BigDecimal n(List<Object> a, int i, String name) {
        if (i >= a.size()) {
            throw new ArgumentException("missing argument " + (i + 1));
        }
        Object o = a.get(i);
        if (o == null) {
            return null;
        }
        return num(o, name);
    }

    private static String s(List<Object> a, int i, String name) {
        if (i >= a.size()) {
            throw new ArgumentException("missing argument " + (i + 1));
        }
        Object o = a.get(i);
        if (o == null) {
            return null;
        }
        if (o instanceof String s) {
            return s;
        }
        throw new ArgumentException("expected text but found " + Values.typeName(o));
    }

    private static List<?> l(List<Object> a, int i, String name) {
        if (i >= a.size()) {
            throw new ArgumentException("missing argument " + (i + 1));
        }
        Object o = a.get(i);
        if (o == null) {
            return null;
        }
        if (o instanceof List<?> l) {
            return l;
        }
        throw new ArgumentException("expected a list but found " + Values.typeName(o));
    }

    private static Map<?, ?> m(List<Object> a, int i, String name) {
        if (i >= a.size()) {
            throw new ArgumentException("missing argument " + (i + 1));
        }
        Object o = a.get(i);
        if (o == null) {
            return null;
        }
        if (o instanceof Map<?, ?> m) {
            return m;
        }
        throw new ArgumentException("expected a context but found " + Values.typeName(o));
    }

    /** Aggregate functions accept either one list or several scalar arguments. */
    private static List<Object> flat(List<Object> a) {
        if (a.size() == 1 && a.get(0) instanceof List<?> l) {
            return new ArrayList<>(l);
        }
        return a;
    }

    private static Object extreme(List<Object> l, boolean min) {
        Object best = null;
        for (Object o : l) {
            if (o == null) {
                return null;
            }
            if (best == null) {
                best = o;
                continue;
            }
            Integer c = Values.compare(o, best);
            if (c == null) {
                throw new ArgumentException("values are not comparable");
            }
            if (min ? c < 0 : c > 0) {
                best = o;
            }
        }
        return best;
    }

    private static Object round(List<Object> a, String name, RoundingMode mode) {
        BigDecimal x = n(a, 0, name);
        if (x == null) {
            return null;
        }
        int scale = 0;
        if (a.size() > 1 && a.get(1) != null) {
            scale = n(a, 1, name).intValueExact();
            if (Math.abs(scale) > 100) {
                throw new ArgumentException("scale out of range");
            }
        }
        return x.setScale(scale, mode);
    }

    private static Object parity(List<Object> a, String name, boolean even) {
        arity(a, 1, name);
        BigDecimal x = n(a, 0, name);
        if (x == null) {
            return null;
        }
        try {
            boolean isEven = x.toBigIntegerExact().testBit(0) == false;
            return even == isEven;
        } catch (ArithmeticException e) {
            throw new ArgumentException("expected an integer");
        }
    }

    private static boolean containsValue(List<Object> list, Object o) {
        for (Object e : list) {
            if (Values.valueEquals(e, o)) {
                return true;
            }
        }
        return false;
    }

    private static void flattenInto(List<?> in, List<Object> out, int depth) {
        if (depth > 32) {
            throw new ArgumentException("lists are nested too deeply");
        }
        for (Object o : in) {
            if (o instanceof List<?> inner) {
                flattenInto(inner, out, depth + 1);
            } else {
                out.add(o);
            }
        }
    }

    private static List<Object> bounded(List<Object> l) {
        if (l.size() > MAX_LIST) {
            throw new ArgumentException("result is too large");
        }
        return l;
    }
}
