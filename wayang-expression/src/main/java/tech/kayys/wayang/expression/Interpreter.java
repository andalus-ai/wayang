package tech.kayys.wayang.expression;

import java.math.BigDecimal;
import java.math.MathContext;
import java.time.Duration;
import java.time.LocalDate;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.Period;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.expression.Node.BinOp;
import tech.kayys.wayang.expression.Node.TestNode;

/** Tree-walking evaluator. One instance per evaluation; not thread-safe. */
final class Interpreter {

    private final String source;
    private boolean inputReferenced;

    Interpreter(String source) {
        this.source = source;
    }

    /** Reports an evaluation problem: throws in strict mode, otherwise yields null. */
    Object fail(Scope scope, String message, int pos) {
        if (scope.strict()) {
            throw new ExpressionException(message, source, pos);
        }
        return null;
    }

    Object eval(Node node, Scope scope) {
        scope.budget().consume(source);
        return switch (node) {
            case Node.Literal l -> l.value();
            case Node.Name n -> name(n, scope);
            case Node.Input ignored -> {
                inputReferenced = true;
                yield scope.hasInput() ? scope.input()
                        : fail(scope, "'?' is only valid inside unary tests", -1);
            }
            case Node.Negate n -> negate(n, scope);
            case Node.Binary b -> binary(b, scope);
            case Node.Between b -> between(b, scope);
            case Node.InTest t -> inTest(t, scope);
            case Node.If i -> Boolean.TRUE.equals(eval(i.condition(), scope))
                    ? eval(i.thenBranch(), scope)
                    : eval(i.elseBranch(), scope);
            case Node.Quantified q -> quantified(q, scope);
            case Node.Path p -> path(p, scope);
            case Node.Index i -> index(i, scope);
            case Node.Call c -> call(c, scope);
            case Node.ListLiteral l -> {
                List<Object> out = new ArrayList<>(l.items().size());
                for (Node item : l.items()) {
                    out.add(eval(item, scope));
                }
                yield out;
            }
            case Node.ContextLiteral c -> context(c, scope);
            case Node.RangeLiteral r -> new Range(
                    eval(r.start(), scope), r.startIncluded(), eval(r.end(), scope), r.endIncluded());
        };
    }

    // ------------------------------------------------------------------ names

    private Object name(Node.Name n, Scope scope) {
        Object v = scope.lookup(n.name());
        if (v == Scope.MISSING) {
            return fail(scope, "Unknown variable '" + n.name() + "'", n.pos());
        }
        return v;
    }

    private Object path(Node.Path p, Scope scope) {
        Object base = eval(p.base(), scope);
        return property(base, p.name(), scope, p.pos());
    }

    Object property(Object base, String name, Scope scope, int pos) {
        if (base == null) {
            return null;
        }
        if (base instanceof Map<?, ?> m) {
            return m.get(name);
        }
        if (base instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            for (Object o : l) {
                out.add(property(o, name, scope, pos));
            }
            return out;
        }
        if (base instanceof LocalDate d) {
            switch (name) {
                case "year":
                    return BigDecimal.valueOf(d.getYear());
                case "month":
                    return BigDecimal.valueOf(d.getMonthValue());
                case "day":
                    return BigDecimal.valueOf(d.getDayOfMonth());
                case "weekday":
                    return BigDecimal.valueOf(d.getDayOfWeek().getValue());
                default:
                    break;
            }
        } else if (base instanceof OffsetDateTime d) {
            switch (name) {
                case "year":
                    return BigDecimal.valueOf(d.getYear());
                case "month":
                    return BigDecimal.valueOf(d.getMonthValue());
                case "day":
                    return BigDecimal.valueOf(d.getDayOfMonth());
                case "weekday":
                    return BigDecimal.valueOf(d.getDayOfWeek().getValue());
                case "hour":
                    return BigDecimal.valueOf(d.getHour());
                case "minute":
                    return BigDecimal.valueOf(d.getMinute());
                case "second":
                    return BigDecimal.valueOf(d.getSecond());
                default:
                    break;
            }
        } else if (base instanceof LocalTime t) {
            switch (name) {
                case "hour":
                    return BigDecimal.valueOf(t.getHour());
                case "minute":
                    return BigDecimal.valueOf(t.getMinute());
                case "second":
                    return BigDecimal.valueOf(t.getSecond());
                default:
                    break;
            }
        } else if (base instanceof Duration d) {
            switch (name) {
                case "days":
                    return BigDecimal.valueOf(d.toDays());
                case "hours":
                    return BigDecimal.valueOf(d.toHoursPart());
                case "minutes":
                    return BigDecimal.valueOf(d.toMinutesPart());
                case "seconds":
                    return BigDecimal.valueOf(d.toSecondsPart());
                default:
                    break;
            }
        } else if (base instanceof Period d) {
            switch (name) {
                case "years":
                    return BigDecimal.valueOf(d.getYears());
                case "months":
                    return BigDecimal.valueOf(d.getMonths());
                default:
                    break;
            }
        } else if (base instanceof Range r) {
            switch (name) {
                case "start":
                    return r.start();
                case "end":
                    return r.end();
                case "start included":
                    return r.startIncluded();
                case "end included":
                    return r.endIncluded();
                default:
                    break;
            }
        }
        return fail(scope, "Cannot read '" + name + "' from a value of type " + Values.typeName(base), pos);
    }

    private Object index(Node.Index node, Scope scope) {
        Object base = eval(node.base(), scope);
        if (base == null) {
            return null;
        }
        List<?> list = base instanceof List<?> l ? l : List.of(base);

        Object outer = null;
        try {
            outer = eval(node.predicate(), scope.withStrict(false));
        } catch (LimitExceededException e) {
            throw e;
        } catch (ExpressionException ignored) {
            // not evaluable without the item context => it is a filter predicate
        }
        if (outer instanceof BigDecimal n) {
            int idx;
            try {
                idx = n.intValueExact();
            } catch (ArithmeticException e) {
                return fail(scope, "List index must be an integer but was " + n, node.pos());
            }
            int size = list.size();
            if (idx > 0 && idx <= size) {
                return list.get(idx - 1);
            }
            if (idx < 0 && -idx <= size) {
                return list.get(size + idx);
            }
            return fail(scope, "List index " + idx + " is out of range for a list of " + size, node.pos());
        }

        List<Object> out = new ArrayList<>();
        for (Object item : list) {
            Map<String, Object> bindings = new LinkedHashMap<>();
            if (item instanceof Map<?, ?> m) {
                m.forEach((k, v) -> bindings.put(String.valueOf(k), v));
            }
            bindings.put("item", item);
            Scope child = scope.child(bindings);
            if (Boolean.TRUE.equals(eval(node.predicate(), child))) {
                out.add(item);
            }
        }
        return out;
    }

    private Object call(Node.Call c, Scope scope) {
        List<Object> args = new ArrayList<>(c.args().size());
        for (Node a : c.args()) {
            args.add(eval(a, scope));
        }
        return Builtins.call(c.function(), args, scope, this, c.pos());
    }

    private Object context(Node.ContextLiteral c, Scope scope) {
        Map<String, Object> out = new LinkedHashMap<>();
        Scope current = scope;
        for (Node.ContextEntry e : c.entries()) {
            Object v = eval(e.value(), current);
            out.put(e.key(), v);
            current = current.child(e.key(), v);
        }
        return out;
    }

    // -------------------------------------------------------------- operators

    private Object negate(Node.Negate n, Scope scope) {
        Object v = eval(n.operand(), scope);
        if (v == null) {
            return null;
        }
        if (v instanceof BigDecimal d) {
            return d.negate();
        }
        if (v instanceof Duration d) {
            return d.negated();
        }
        if (v instanceof Period p) {
            return p.negated();
        }
        return fail(scope, "Cannot negate a value of type " + Values.typeName(v), n.pos());
    }

    private Object binary(Node.Binary b, Scope scope) {
        BinOp op = b.op();
        if (op == BinOp.AND || op == BinOp.OR) {
            return logical(b, scope);
        }
        Object l = eval(b.left(), scope);
        Object r = eval(b.right(), scope);
        switch (op) {
            case EQ:
                return equality(l, r);
            case NE: {
                Boolean eq = equality(l, r);
                return eq == null ? null : !eq;
            }
            case LT:
            case LE:
            case GT:
            case GE:
                return relational(op, l, r, scope, b.pos());
            case ADD:
                return add(l, r, scope, b.pos());
            case SUB:
                return subtract(l, r, scope, b.pos());
            case MUL:
            case DIV:
            case POW:
                return arithmetic(op, l, r, scope, b.pos());
            default:
                throw new IllegalStateException("Unhandled operator " + op);
        }
    }

    private Object logical(Node.Binary b, Scope scope) {
        boolean and = b.op() == BinOp.AND;
        Boolean l = asBoolean(eval(b.left(), scope), scope, b.pos());
        if (l != null && l == !and) {
            return !and; // false and x => false ; true or x => true
        }
        Boolean r = asBoolean(eval(b.right(), scope), scope, b.pos());
        if (r != null && r == !and) {
            return !and;
        }
        if (l != null && r != null) {
            return and; // both are the neutral value
        }
        return null;
    }

    private Boolean asBoolean(Object v, Scope scope, int pos) {
        if (v == null) {
            return null;
        }
        if (v instanceof Boolean b) {
            return b;
        }
        fail(scope, "Expected a boolean but found " + Values.typeName(v), pos);
        return null;
    }

    private Boolean equality(Object l, Object r) {
        return Values.valueEquals(l, r);
    }

    private Object relational(BinOp op, Object l, Object r, Scope scope, int pos) {
        if (l == null || r == null) {
            return null;
        }
        Integer c = Values.compare(l, r);
        if (c == null) {
            return fail(scope,
                    "Cannot compare " + Values.typeName(l) + " with " + Values.typeName(r), pos);
        }
        return switch (op) {
            case LT -> c < 0;
            case LE -> c <= 0;
            case GT -> c > 0;
            default -> c >= 0;
        };
    }

    private Object add(Object l, Object r, Scope scope, int pos) {
        if (l == null || r == null) {
            return null;
        }
        if (l instanceof BigDecimal a && r instanceof BigDecimal b) {
            return a.add(b);
        }
        if (l instanceof String || r instanceof String) {
            // deliberate leniency over FEEL: "id-" + 5 concatenates instead of yielding null
            if (isScalarText(l) && isScalarText(r)) {
                return Values.asString(l) + Values.asString(r);
            }
        }
        Object temporal = temporal(l, r, 1);
        if (temporal != NO_RESULT) {
            return temporal;
        }
        temporal = temporal(r, l, 1);
        if (temporal != NO_RESULT && !(l instanceof Duration && r instanceof Duration)) {
            return temporal;
        }
        if (l instanceof Duration a && r instanceof Duration b) {
            return a.plus(b);
        }
        if (l instanceof Period a && r instanceof Period b) {
            return a.plus(b);
        }
        return fail(scope, "Cannot add " + Values.typeName(r) + " to " + Values.typeName(l), pos);
    }

    private static boolean isScalarText(Object v) {
        return v instanceof String || v instanceof BigDecimal || v instanceof Boolean
                || v instanceof LocalDate || v instanceof LocalTime || v instanceof OffsetDateTime;
    }

    private Object subtract(Object l, Object r, Scope scope, int pos) {
        if (l == null || r == null) {
            return null;
        }
        if (l instanceof BigDecimal a && r instanceof BigDecimal b) {
            return a.subtract(b);
        }
        if (l instanceof LocalDate a && r instanceof LocalDate b) {
            return Duration.ofDays(ChronoUnit.DAYS.between(b, a));
        }
        if (l instanceof OffsetDateTime a && r instanceof OffsetDateTime b) {
            return Duration.between(b, a);
        }
        if (l instanceof Duration a && r instanceof Duration b) {
            return a.minus(b);
        }
        if (l instanceof Period a && r instanceof Period b) {
            return a.minus(b);
        }
        Object temporal = temporal(l, r, -1);
        if (temporal != NO_RESULT) {
            return temporal;
        }
        return fail(scope, "Cannot subtract " + Values.typeName(r) + " from " + Values.typeName(l), pos);
    }

    private static final Object NO_RESULT = new Object();

    /** date/time +- duration/period; {@code sign} is +1 or -1. */
    private static Object temporal(Object base, Object amount, int sign) {
        if (base instanceof LocalDate d) {
            if (amount instanceof Period p) {
                return sign > 0 ? d.plus(p) : d.minus(p);
            }
            if (amount instanceof Duration du) {
                return d.plusDays(sign * du.toDays());
            }
        } else if (base instanceof OffsetDateTime d) {
            if (amount instanceof Period p) {
                return sign > 0 ? d.plus(p) : d.minus(p);
            }
            if (amount instanceof Duration du) {
                return sign > 0 ? d.plus(du) : d.minus(du);
            }
        } else if (base instanceof LocalTime t && amount instanceof Duration du) {
            return sign > 0 ? t.plus(du) : t.minus(du);
        }
        return NO_RESULT;
    }

    private Object arithmetic(BinOp op, Object l, Object r, Scope scope, int pos) {
        if (l == null || r == null) {
            return null;
        }
        if (!(l instanceof BigDecimal a) || !(r instanceof BigDecimal b)) {
            return fail(scope, "Operator needs numbers but found " + Values.typeName(l) + " and "
                    + Values.typeName(r), pos);
        }
        switch (op) {
            case MUL:
                return a.multiply(b);
            case DIV:
                if (b.signum() == 0) {
                    return fail(scope, "Division by zero", pos);
                }
                return a.divide(b, MathContext.DECIMAL128);
            default: {
                int exp;
                try {
                    exp = b.intValueExact();
                } catch (ArithmeticException e) {
                    double d = Math.pow(a.doubleValue(), b.doubleValue());
                    if (Double.isNaN(d) || Double.isInfinite(d)) {
                        return fail(scope, "Result of ** is not a finite number", pos);
                    }
                    return new BigDecimal(Double.toString(d));
                }
                if (Math.abs(exp) > 999) {
                    return fail(scope, "Exponent out of range (|n| <= 999)", pos);
                }
                if (exp >= 0) {
                    return a.pow(exp, MathContext.DECIMAL128);
                }
                if (a.signum() == 0) {
                    return fail(scope, "Division by zero", pos);
                }
                return BigDecimal.ONE.divide(a.pow(-exp, MathContext.DECIMAL128), MathContext.DECIMAL128);
            }
        }
    }

    private Object between(Node.Between b, Scope scope) {
        Object v = eval(b.value(), scope);
        Object lo = eval(b.low(), scope);
        Object hi = eval(b.high(), scope);
        if (v == null || lo == null || hi == null) {
            return null;
        }
        Integer c1 = Values.compare(v, lo);
        Integer c2 = Values.compare(v, hi);
        if (c1 == null || c2 == null) {
            return fail(scope, "Cannot compare " + Values.typeName(v) + " with a range of "
                    + Values.typeName(lo), b.pos());
        }
        return c1 >= 0 && c2 <= 0;
    }

    private Object quantified(Node.Quantified q, Scope scope) {
        Object src = eval(q.source(), scope);
        if (src == null) {
            return null;
        }
        List<?> list = src instanceof List<?> l ? l : List.of(src);
        boolean sawNull = false;
        for (Object item : list) {
            Object v = eval(q.satisfies(), scope.child(q.variable(), item));
            if (v == null) {
                sawNull = true;
            } else if (v instanceof Boolean bv) {
                if (q.some() && bv) {
                    return true;
                }
                if (!q.some() && !bv) {
                    return false;
                }
            } else {
                sawNull = true;
            }
        }
        if (sawNull) {
            return null;
        }
        return !q.some();
    }

    // ------------------------------------------------------------ unary tests

    private Object inTest(Node.InTest t, Scope scope) {
        Object v = eval(t.value(), scope);
        return matches(t.tests(), v, scope);
    }

    boolean matches(List<TestNode> tests, Object input, Scope scope) {
        for (TestNode t : tests) {
            if (matches(t, input, scope)) {
                return true;
            }
        }
        return false;
    }

    private boolean matches(TestNode test, Object input, Scope scope) {
        return switch (test) {
            case Node.AnyTest ignored -> true;
            case Node.NotTest n -> !matches(n.tests(), input, scope);
            case Node.CompareTest c -> {
                Object rhs = eval(c.operand(), scope);
                yield switch (c.op()) {
                    case EQ -> Values.valueEquals(input, rhs);
                    case NE -> !Values.valueEquals(input, rhs);
                    default -> Boolean.TRUE.equals(relational(c.op(), input, rhs, scope.withStrict(false), -1));
                };
            }
            case Node.ExprTest e -> {
                boolean saved = inputReferenced;
                inputReferenced = false;
                Object v = eval(e.expression(), scope.withInput(input));
                boolean usedInput = inputReferenced;
                inputReferenced = saved || usedInput;
                yield matchesValue(v, input, usedInput);
            }
        };
    }

    private static boolean matchesValue(Object v, Object input, boolean usedInput) {
        if (v instanceof Boolean b && usedInput) {
            return b;
        }
        if (v instanceof Range r) {
            return Boolean.TRUE.equals(r.includes(input));
        }
        if (v instanceof List<?> list) {
            for (Object o : list) {
                if (o instanceof Range r) {
                    if (Boolean.TRUE.equals(r.includes(input))) {
                        return true;
                    }
                } else if (Values.valueEquals(input, o)) {
                    return true;
                }
            }
            return false;
        }
        return Values.valueEquals(input, v);
    }
}
