package tech.kayys.wayang.expression;

import java.math.BigDecimal;
import java.math.BigInteger;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalDateTime;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Value model of the Wayang expression language.
 *
 * <p>
 * All numbers are {@link BigDecimal}; text is {@link String}; logic is
 * {@link Boolean}; collections are {@code List<Object>} and
 * {@code Map<String,Object>}. Dates and times use {@code java.time} types.
 * There is deliberately no reflection-based bean access: only maps and lists can
 * be navigated, which keeps evaluation deterministic and bounded.
 */
public final class Values {

    private Values() {
    }

    /** Converts host values (Integer, Long, Double, ...) into the language's value model. */
    @SuppressWarnings("unchecked")
    public static Object normalize(Object v) {
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal || v instanceof String || v instanceof Boolean) {
            return v;
        }
        if (v instanceof BigInteger bi) {
            return new BigDecimal(bi);
        }
        if (v instanceof Integer || v instanceof Long || v instanceof Short || v instanceof Byte) {
            return BigDecimal.valueOf(((Number) v).longValue());
        }
        if (v instanceof Double || v instanceof Float) {
            double d = ((Number) v).doubleValue();
            if (Double.isNaN(d) || Double.isInfinite(d)) {
                return null;
            }
            return new BigDecimal(Double.toString(d));
        }
        if (v instanceof Number n) {
            return new BigDecimal(n.toString());
        }
        if (v instanceof Character c) {
            return c.toString();
        }
        if (v instanceof Enum<?> e) {
            return e.name();
        }
        if (v instanceof java.time.Instant i) {
            return OffsetDateTime.ofInstant(i, ZoneOffset.UTC);
        }
        if (v instanceof LocalDate || v instanceof LocalTime || v instanceof OffsetDateTime
                || v instanceof Duration || v instanceof Period || v instanceof Range) {
            return v;
        }
        if (v instanceof LocalDateTime ldt) {
            return ldt.atOffset(ZoneOffset.UTC);
        }
        if (v instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            for (Map.Entry<?, ?> e : m.entrySet()) {
                out.put(String.valueOf(e.getKey()), normalize(e.getValue()));
            }
            return out;
        }
        if (v instanceof Iterable<?> it) {
            List<Object> out = new ArrayList<>();
            for (Object o : it) {
                out.add(normalize(o));
            }
            return out;
        }
        if (v instanceof Object[] arr) {
            List<Object> out = new ArrayList<>(arr.length);
            for (Object o : arr) {
                out.add(normalize(o));
            }
            return out;
        }
        throw new IllegalArgumentException(
                "Unsupported value type for expressions: " + v.getClass().getName()
                        + " (convert it to a Map/List/primitive first)");
    }

    /** Language-level type name, used in diagnostics. */
    public static String typeName(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof BigDecimal) {
            return "number";
        }
        if (v instanceof String) {
            return "string";
        }
        if (v instanceof Boolean) {
            return "boolean";
        }
        if (v instanceof LocalDate) {
            return "date";
        }
        if (v instanceof LocalTime) {
            return "time";
        }
        if (v instanceof OffsetDateTime) {
            return "date and time";
        }
        if (v instanceof Duration || v instanceof Period) {
            return "duration";
        }
        if (v instanceof List<?>) {
            return "list";
        }
        if (v instanceof Map<?, ?>) {
            return "context";
        }
        if (v instanceof Range) {
            return "range";
        }
        return v.getClass().getSimpleName();
    }

    /** FEEL-style equality: numbers compare by value, lists and contexts structurally. */
    public static boolean valueEquals(Object a, Object b) {
        if (a == null || b == null) {
            return a == b;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y) == 0;
        }
        if (a instanceof List<?> la && b instanceof List<?> lb) {
            if (la.size() != lb.size()) {
                return false;
            }
            for (int i = 0; i < la.size(); i++) {
                if (!valueEquals(la.get(i), lb.get(i))) {
                    return false;
                }
            }
            return true;
        }
        if (a instanceof Map<?, ?> ma && b instanceof Map<?, ?> mb) {
            if (ma.size() != mb.size()) {
                return false;
            }
            for (Map.Entry<?, ?> e : ma.entrySet()) {
                if (!mb.containsKey(e.getKey()) || !valueEquals(e.getValue(), mb.get(e.getKey()))) {
                    return false;
                }
            }
            return true;
        }
        return Objects.equals(a, b);
    }

    /**
     * Orders two values of the same comparable type.
     *
     * @return negative/zero/positive, or {@code null} if the values are not comparable
     */
    public static Integer compare(Object a, Object b) {
        if (a == null || b == null) {
            return null;
        }
        if (a instanceof BigDecimal x && b instanceof BigDecimal y) {
            return x.compareTo(y);
        }
        if (a instanceof String x && b instanceof String y) {
            return Integer.signum(x.compareTo(y));
        }
        if (a instanceof LocalDate x && b instanceof LocalDate y) {
            return x.compareTo(y);
        }
        if (a instanceof LocalTime x && b instanceof LocalTime y) {
            return x.compareTo(y);
        }
        if (a instanceof OffsetDateTime x && b instanceof OffsetDateTime y) {
            return x.toInstant().compareTo(y.toInstant());
        }
        if (a instanceof Duration x && b instanceof Duration y) {
            return x.compareTo(y);
        }
        if (a instanceof Period x && b instanceof Period y) {
            return Long.compare(x.toTotalMonths(), y.toTotalMonths());
        }
        if (a instanceof Boolean x && b instanceof Boolean y) {
            return Boolean.compare(x, y);
        }
        return null;
    }

    /** Canonical text form (used by string concatenation and {@code string()}). */
    public static String asString(Object v) {
        if (v == null) {
            return "null";
        }
        if (v instanceof BigDecimal n) {
            BigDecimal s = n.stripTrailingZeros();
            return s.scale() < 0 ? s.setScale(0).toPlainString() : s.toPlainString();
        }
        if (v instanceof List<?> l) {
            StringBuilder sb = new StringBuilder("[");
            for (int i = 0; i < l.size(); i++) {
                if (i > 0) {
                    sb.append(", ");
                }
                sb.append(v instanceof String ? "\"" + l.get(i) + "\"" : asString(l.get(i)));
            }
            return sb.append(']').toString();
        }
        if (v instanceof Map<?, ?> m) {
            StringBuilder sb = new StringBuilder("{");
            boolean first = true;
            for (Map.Entry<?, ?> e : m.entrySet()) {
                if (!first) {
                    sb.append(", ");
                }
                first = false;
                sb.append(e.getKey()).append(": ").append(asString(e.getValue()));
            }
            return sb.append('}').toString();
        }
        return v.toString();
    }
}
