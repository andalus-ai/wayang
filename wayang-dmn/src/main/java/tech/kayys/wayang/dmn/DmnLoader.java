package tech.kayys.wayang.dmn;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * Builds {@link DecisionModel}s from plain Map/List structures, i.e. what a
 * JSON or YAML parser produces. This is Wayang's native, low-code decision
 * format (what the Studio stores); it is <em>not</em> the DMN XML interchange
 * format.
 *
 * <pre>
 * id: loan
 * decisions:
 *   - name: risk
 *     type: table            # or: literal
 *     hitPolicy: FIRST       # UNIQUE|FIRST|ANY|COLLECT|RULE_ORDER
 *     aggregator: SUM        # optional, COLLECT only
 *     inputs: ["applicant.age", "applicant.income"]
 *     outputs: ["risk"]
 *     default: '"unknown"'   # optional
 *     rules:
 *       - when: ["&lt; 25", "&lt; 20000"]
 *         then: ['"high"']
 *   - name: approved
 *     type: literal
 *     expression: 'risk != "high"'
 * </pre>
 */
public final class DmnLoader {

    private DmnLoader() {
    }

    public static DecisionModel load(Map<String, ?> doc) {
        String id = string(doc, "id", "model");
        DecisionModel.Builder mb = DecisionModel.builder(id);
        for (Object o : list(doc, "decisions")) {
            Map<String, ?> d = asMap(o, "decision");
            String name = requireString(d, "name");
            String type = string(d, "type", "table");
            switch (type) {
                case "literal" -> mb.literal(name, requireString(d, "expression"));
                case "table" -> mb.table(table(name, d));
                default -> throw new DmnException("Decision '" + name + "': unknown type '" + type + "'");
            }
        }
        return mb.build();
    }

    public static DecisionTable table(String name, Map<String, ?> d) {
        DecisionTable.Builder b = DecisionTable.builder(name);
        try {
            b.hitPolicy(HitPolicy.valueOf(string(d, "hitPolicy", "UNIQUE").toUpperCase(java.util.Locale.ROOT)));
            b.aggregator(HitPolicy.Aggregator.valueOf(string(d, "aggregator", "NONE").toUpperCase(java.util.Locale.ROOT)));
        } catch (IllegalArgumentException e) {
            throw new DmnException("Decision '" + name + "': " + e.getMessage(), e);
        }
        for (Object i : list(d, "inputs")) {
            b.input(String.valueOf(i));
        }
        for (Object o : list(d, "outputs")) {
            b.output(String.valueOf(o));
        }
        if (d.get("default") != null) {
            b.defaultOutput(String.valueOf(d.get("default")));
        }
        for (Object r : list(d, "rules")) {
            Map<String, ?> rm = asMap(r, "rule");
            b.rule(new Rule(strings(rm.get("when")), strings(rm.get("then")),
                    rm.get("note") == null ? null : String.valueOf(rm.get("note"))));
        }
        return b.build();
    }

    private static List<String> strings(Object o) {
        if (!(o instanceof List<?> l)) {
            throw new DmnException("Expected a list but got " + o);
        }
        List<String> out = new ArrayList<>();
        for (Object e : l) {
            out.add(String.valueOf(e));
        }
        return out;
    }

    private static List<?> list(Map<String, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            return List.of();
        }
        if (v instanceof List<?> l) {
            return l;
        }
        throw new DmnException("'" + key + "' must be a list");
    }

    @SuppressWarnings("unchecked")
    private static Map<String, ?> asMap(Object o, String what) {
        if (o instanceof Map<?, ?> m) {
            return (Map<String, ?>) m;
        }
        throw new DmnException("Expected a " + what + " object but got " + o);
    }

    private static String string(Map<String, ?> m, String key, String dflt) {
        Object v = m.get(key);
        return v == null ? dflt : String.valueOf(v);
    }

    private static String requireString(Map<String, ?> m, String key) {
        Object v = m.get(key);
        if (v == null) {
            throw new DmnException("Missing required field '" + key + "'");
        }
        return String.valueOf(v);
    }
}
