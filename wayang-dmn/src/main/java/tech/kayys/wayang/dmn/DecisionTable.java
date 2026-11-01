package tech.kayys.wayang.dmn;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

import tech.kayys.wayang.expression.Expression;
import tech.kayys.wayang.expression.ExpressionException;
import tech.kayys.wayang.expression.Expressions;
import tech.kayys.wayang.expression.Scope;
import tech.kayys.wayang.expression.UnaryTests;
import tech.kayys.wayang.expression.Values;

/**
 * An immutable, pre-compiled DMN decision table. All entries are parsed at
 * construction time, so syntax errors surface when the model is deployed, not
 * when a process instance hits the decision.
 */
public final class DecisionTable {

    private final String name;
    private final List<String> inputExpressionSources;
    private final List<String> outputNames;
    private final HitPolicy hitPolicy;
    private final HitPolicy.Aggregator aggregator;
    private final List<Rule> rules;
    private final String defaultOutputSource;

    private final List<Expression> inputExpressions;
    private final List<List<UnaryTests>> compiledInputs;
    private final List<List<Expression>> compiledOutputs;
    private final List<Expression> compiledDefault;

    private DecisionTable(Builder b) {
        this.name = Objects.requireNonNull(b.name, "name");
        this.inputExpressionSources = List.copyOf(b.inputs);
        this.outputNames = List.copyOf(b.outputs);
        this.hitPolicy = b.hitPolicy;
        this.aggregator = b.aggregator;
        this.rules = List.copyOf(b.rules);
        this.defaultOutputSource = b.defaultOutput;
        if (outputNames.isEmpty()) {
            throw new DmnException("Decision table '" + name + "' needs at least one output");
        }
        if (aggregator != HitPolicy.Aggregator.NONE && hitPolicy != HitPolicy.COLLECT) {
            throw new DmnException("Decision table '" + name + "': an aggregator requires hit policy COLLECT");
        }
        if (aggregator != HitPolicy.Aggregator.NONE && aggregator != HitPolicy.Aggregator.COUNT && outputNames.size() != 1) {
            throw new DmnException("Decision table '" + name + "': aggregator " + aggregator + " needs exactly one output");
        }
        this.inputExpressions = new ArrayList<>();
        for (String src : inputExpressionSources) {
            this.inputExpressions.add(compile(src, "input expression"));
        }
        this.compiledInputs = new ArrayList<>();
        this.compiledOutputs = new ArrayList<>();
        int i = 0;
        for (Rule r : rules) {
            i++;
            if (r.inputEntries().size() != inputExpressionSources.size()) {
                throw new DmnException("Decision table '" + name + "' rule " + i + " has " + r.inputEntries().size()
                        + " input entries, expected " + inputExpressionSources.size());
            }
            if (r.outputEntries().size() != outputNames.size()) {
                throw new DmnException("Decision table '" + name + "' rule " + i + " has " + r.outputEntries().size()
                        + " output entries, expected " + outputNames.size());
            }
            List<UnaryTests> in = new ArrayList<>();
            for (String e : r.inputEntries()) {
                try {
                    in.add(Expressions.parseUnaryTests(e));
                } catch (ExpressionException ex) {
                    throw new DmnException("Decision table '" + name + "' rule " + i + " input entry '" + e + "': "
                            + ex.getMessage(), ex);
                }
            }
            compiledInputs.add(in);
            List<Expression> out = new ArrayList<>();
            for (String e : r.outputEntries()) {
                out.add(compile(e, "rule " + i + " output entry"));
            }
            compiledOutputs.add(out);
        }
        if (defaultOutputSource != null) {
            this.compiledDefault = List.of(compile(defaultOutputSource, "default output"));
        } else {
            this.compiledDefault = List.of();
        }
    }

    private Expression compile(String src, String what) {
        try {
            return Expressions.parse(src);
        } catch (ExpressionException e) {
            throw new DmnException("Decision table '" + name + "' " + what + " '" + src + "': " + e.getMessage(), e);
        }
    }

    public String name() {
        return name;
    }

    public HitPolicy hitPolicy() {
        return hitPolicy;
    }

    public List<String> outputNames() {
        return outputNames;
    }

    public List<Rule> rules() {
        return rules;
    }

    /** Free variable names used by input expressions and output entries (for dependency analysis). */
    public java.util.Set<String> referencedNames() {
        java.util.Set<String> out = new java.util.LinkedHashSet<>();
        inputExpressions.forEach(e -> out.addAll(e.referencedNames()));
        compiledOutputs.forEach(l -> l.forEach(e -> out.addAll(e.referencedNames())));
        compiledInputs.forEach(l -> {
        });
        return out;
    }

    public DecisionResult evaluate(Map<String, ?> variables) {
        return evaluate(Scope.of(variables));
    }

    public DecisionResult evaluate(Scope scope) {
        List<Object> inputs = new ArrayList<>();
        for (Expression e : inputExpressions) {
            inputs.add(e.evaluate(scope));
        }
        List<Integer> matched = new ArrayList<>();
        for (int r = 0; r < rules.size(); r++) {
            if (matches(r, inputs, scope)) {
                matched.add(r);
                if (hitPolicy == HitPolicy.FIRST) {
                    break;
                }
            }
        }
        List<Integer> oneBased = matched.stream().map(i -> i + 1).toList();
        if (matched.isEmpty()) {
            return new DecisionResult(defaultOrNull(scope), oneBased);
        }
        switch (hitPolicy) {
            case UNIQUE:
                if (matched.size() > 1) {
                    throw new DmnException("Decision '" + name + "': UNIQUE hit policy violated, rules " + oneBased
                            + " all matched");
                }
                return new DecisionResult(output(matched.get(0), scope), oneBased);
            case FIRST:
                return new DecisionResult(output(matched.get(0), scope), oneBased);
            case ANY: {
                Object first = output(matched.get(0), scope);
                for (int i = 1; i < matched.size(); i++) {
                    if (!Values.valueEquals(first, output(matched.get(i), scope))) {
                        throw new DmnException("Decision '" + name + "': ANY hit policy violated, rules " + oneBased
                                + " produced different outputs");
                    }
                }
                return new DecisionResult(first, oneBased);
            }
            case COLLECT:
            case RULE_ORDER: {
                List<Object> all = new ArrayList<>();
                for (int idx : matched) {
                    all.add(output(idx, scope));
                }
                return new DecisionResult(aggregate(all), oneBased);
            }
            default:
                throw new IllegalStateException();
        }
    }

    private boolean matches(int rule, List<Object> inputs, Scope scope) {
        List<UnaryTests> tests = compiledInputs.get(rule);
        for (int i = 0; i < tests.size(); i++) {
            if (!tests.get(i).matches(inputs.get(i), scope)) {
                return false;
            }
        }
        return true;
    }

    private Object output(int rule, Scope scope) {
        List<Expression> exprs = compiledOutputs.get(rule);
        if (exprs.size() == 1) {
            return exprs.get(0).evaluate(scope);
        }
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < exprs.size(); i++) {
            m.put(outputNames.get(i), exprs.get(i).evaluate(scope));
        }
        return m;
    }

    private Object defaultOrNull(Scope scope) {
        if (compiledDefault.isEmpty()) {
            return hitPolicy == HitPolicy.COLLECT || hitPolicy == HitPolicy.RULE_ORDER
                    ? aggregate(new ArrayList<>()) : null;
        }
        Object v = compiledDefault.get(0).evaluate(scope);
        return v;
    }

    private Object aggregate(List<Object> all) {
        switch (aggregator) {
            case NONE:
                return all;
            case COUNT:
                return BigDecimal.valueOf(all.size());
            case SUM:
            case MIN:
            case MAX: {
                if (all.isEmpty()) {
                    return aggregator == HitPolicy.Aggregator.SUM ? BigDecimal.ZERO : null;
                }
                BigDecimal acc = null;
                for (Object o : all) {
                    if (!(o instanceof BigDecimal b)) {
                        throw new DmnException("Decision '" + name + "': " + aggregator
                                + " aggregation needs numeric outputs but got " + Values.typeName(o));
                    }
                    if (acc == null) {
                        acc = b;
                    } else if (aggregator == HitPolicy.Aggregator.SUM) {
                        acc = acc.add(b);
                    } else if (aggregator == HitPolicy.Aggregator.MIN) {
                        acc = acc.min(b);
                    } else {
                        acc = acc.max(b);
                    }
                }
                return acc;
            }
            default:
                throw new IllegalStateException();
        }
    }

    public static Builder builder(String name) {
        return new Builder(name);
    }

    public static final class Builder {
        private final String name;
        private final List<String> inputs = new ArrayList<>();
        private final List<String> outputs = new ArrayList<>();
        private final List<Rule> rules = new ArrayList<>();
        private HitPolicy hitPolicy = HitPolicy.UNIQUE;
        private HitPolicy.Aggregator aggregator = HitPolicy.Aggregator.NONE;
        private String defaultOutput;

        private Builder(String name) {
            this.name = name;
        }

        /** Adds an input column; the source is an expression (e.g. {@code customer.age}). */
        public Builder input(String expression) {
            inputs.add(expression);
            return this;
        }

        public Builder output(String outputName) {
            outputs.add(outputName);
            return this;
        }

        public Builder hitPolicy(HitPolicy p) {
            this.hitPolicy = p;
            return this;
        }

        public Builder aggregator(HitPolicy.Aggregator a) {
            this.aggregator = a;
            return this;
        }

        /** Output expression used when no rule matches (single-output tables only). */
        public Builder defaultOutput(String expression) {
            this.defaultOutput = expression;
            return this;
        }

        public Builder rule(List<String> inputEntries, List<String> outputEntries) {
            rules.add(Rule.of(inputEntries, outputEntries));
            return this;
        }

        public Builder rule(Rule r) {
            rules.add(r);
            return this;
        }

        public DecisionTable build() {
            return new DecisionTable(this);
        }
    }
}
