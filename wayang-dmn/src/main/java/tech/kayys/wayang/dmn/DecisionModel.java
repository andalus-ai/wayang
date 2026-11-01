package tech.kayys.wayang.dmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.expression.Scope;

/**
 * A decision requirements graph. Dependencies between decisions are inferred
 * from the names each decision references, so a decision called
 * {@code riskScore} that is used as a variable in another decision becomes its
 * prerequisite automatically. Cycles are rejected when the model is built.
 */
public final class DecisionModel {

    private final String id;
    private final Map<String, Decision> decisions;
    private final List<String> order;
    private final Map<String, Set<String>> requires;

    private DecisionModel(String id, Map<String, Decision> decisions) {
        this.id = id;
        this.decisions = Map.copyOf(decisions);
        Map<String, Set<String>> req = new LinkedHashMap<>();
        for (Decision d : decisions.values()) {
            Set<String> deps = new LinkedHashSet<>();
            for (String n : d.referencedNames()) {
                if (decisions.containsKey(n) && !n.equals(d.name())) {
                    deps.add(n);
                } else if (n.equals(d.name()) && decisions.containsKey(n)) {
                    // A decision referencing its own name is only legal if the name is also an input
                    // variable; we cannot know that here, so treat it as a cycle.
                    throw new DmnException("Decision '" + n + "' references itself");
                }
            }
            req.put(d.name(), deps);
        }
        this.requires = req;
        this.order = topologicalOrder(req);
    }

    private static List<String> topologicalOrder(Map<String, Set<String>> req) {
        List<String> out = new ArrayList<>();
        Set<String> done = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String n : req.keySet()) {
            visit(n, req, done, visiting, out);
        }
        return List.copyOf(out);
    }

    private static void visit(String n, Map<String, Set<String>> req, Set<String> done, Set<String> visiting,
            List<String> out) {
        if (done.contains(n)) {
            return;
        }
        if (!visiting.add(n)) {
            throw new DmnException("Cyclic decision dependency involving: " + String.join(" -> ", visiting) + " -> " + n);
        }
        for (String d : req.get(n)) {
            visit(d, req, done, visiting, out);
        }
        visiting.remove(n);
        done.add(n);
        out.add(n);
    }

    public String id() {
        return id;
    }

    /** Decision names in evaluation order (prerequisites first). */
    public List<String> evaluationOrder() {
        return order;
    }

    public Map<String, Set<String>> requirements() {
        return requires;
    }

    /** Names referenced by any decision that are not themselves decisions, i.e. required input data. */
    public Set<String> requiredInputs() {
        Set<String> out = new LinkedHashSet<>();
        for (Decision d : decisions.values()) {
            for (String n : d.referencedNames()) {
                if (!decisions.containsKey(n)) {
                    out.add(n);
                }
            }
        }
        return out;
    }

    /**
     * Evaluates every decision (or only {@code wanted} and what they require)
     * and returns decision name to result.
     */
    public Map<String, Object> evaluate(Map<String, ?> inputs, Set<String> wanted) {
        Set<String> needed = new LinkedHashSet<>();
        if (wanted == null || wanted.isEmpty()) {
            needed.addAll(order);
        } else {
            for (String w : wanted) {
                if (!decisions.containsKey(w)) {
                    throw new DmnException("Unknown decision '" + w + "' in model '" + id + "'");
                }
                collect(w, needed);
            }
        }
        Map<String, Object> vars = new LinkedHashMap<>(inputs);
        Map<String, Object> results = new LinkedHashMap<>();
        for (String name : order) {
            if (!needed.contains(name)) {
                continue;
            }
            Object v = decisions.get(name).evaluate(Scope.of(vars));
            vars.put(name, v);
            results.put(name, v);
        }
        return results;
    }

    public Map<String, Object> evaluate(Map<String, ?> inputs) {
        return evaluate(inputs, Set.of());
    }

    private void collect(String n, Set<String> into) {
        if (!into.add(n)) {
            return;
        }
        for (String d : requires.get(n)) {
            collect(d, into);
        }
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private final Map<String, Decision> decisions = new LinkedHashMap<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder decision(Decision d) {
            if (decisions.putIfAbsent(d.name(), d) != null) {
                throw new DmnException("Duplicate decision name '" + d.name() + "'");
            }
            return this;
        }

        public Builder table(DecisionTable t) {
            return decision(new Decision.Table(t));
        }

        public Builder literal(String name, String expression) {
            return decision(Decision.Literal.of(name, expression));
        }

        public DecisionModel build() {
            return new DecisionModel(id, decisions);
        }
    }
}
