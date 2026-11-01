package tech.kayys.wayang.expression;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Evaluation scope: variable bindings, an injectable clock (so that
 * {@code now()} is testable and replay-deterministic), strictness and a
 * resource budget shared by every scope derived from the same root.
 *
 * <p>
 * Scopes are immutable views; {@code child(...)} and {@code with...} return new
 * scopes. Values handed in are normalized with {@link Values#normalize(Object)}.
 */
public final class Scope {

    /** Default maximum number of evaluation steps per root scope. */
    public static final int DEFAULT_MAX_STEPS = 200_000;

    static final Object MISSING = new Object();

    /** Mutable step counter shared by all scopes of one evaluation tree. */
    static final class Budget {
        private int remaining; // guarded by this: parallel EIP branches share one budget

        Budget(int max) {
            this.remaining = max;
        }

        synchronized void consume(String source) {
            if (--remaining < 0) {
                throw new LimitExceededException(
                        "Evaluation exceeded the step budget; the expression is too expensive", source);
            }
        }
    }

    private final Scope parent;
    private final Map<String, Object> vars;
    private final Budget budget;
    private final Clock clock;
    private final boolean strict;
    private final boolean hasInput;
    private final Object input;

    private Scope(Scope parent, Map<String, Object> vars, Budget budget, Clock clock, boolean strict,
            boolean hasInput, Object input) {
        this.parent = parent;
        this.vars = vars;
        this.budget = budget;
        this.clock = clock;
        this.strict = strict;
        this.hasInput = hasInput;
        this.input = input;
    }

    public static Scope empty() {
        return new Scope(null, Map.of(), new Budget(DEFAULT_MAX_STEPS), Clock.systemUTC(), false, false, null);
    }

    public static Scope of(Map<String, ?> variables) {
        return empty().child(variables);
    }

    /** A derived scope with additional bindings that shadow outer ones. */
    @SuppressWarnings("unchecked")
    public Scope child(Map<String, ?> variables) {
        Map<String, Object> copy = new LinkedHashMap<>();
        if (variables != null) {
            variables.forEach((k, v) -> copy.put(k, Values.normalize(v)));
        }
        return new Scope(this, copy, budget, clock, strict, hasInput, input);
    }

    public Scope child(String name, Object value) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put(name, Values.normalize(value));
        return new Scope(this, m, budget, clock, strict, hasInput, input);
    }

    public Scope withClock(Clock newClock) {
        return new Scope(parent, vars, budget, newClock, strict, hasInput, input);
    }

    /** In strict mode type errors and unknown variables throw instead of yielding null. */
    public Scope withStrict(boolean newStrict) {
        return new Scope(parent, vars, budget, clock, newStrict, hasInput, input);
    }

    /** Restarts the step budget with the given limit for evaluations started on the returned scope. */
    public Scope withMaxSteps(int maxSteps) {
        return new Scope(parent, vars, new Budget(maxSteps), clock, strict, hasInput, input);
    }

    Scope withInput(Object value) {
        return new Scope(this, Map.of(), budget, clock, strict, true, Values.normalize(value));
    }

    Object lookup(String name) {
        for (Scope s = this; s != null; s = s.parent) {
            if (s.vars.containsKey(name)) {
                return s.vars.get(name);
            }
        }
        return MISSING;
    }

    boolean hasInput() {
        return hasInput;
    }

    Object input() {
        return input;
    }

    boolean strict() {
        return strict;
    }

    Clock clock() {
        return clock;
    }

    Budget budget() {
        return budget;
    }
}
