package tech.kayys.wayang.agent;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * The "agent factory": a parameterised agent definition. {@code ${param}}
 * placeholders in string fields are substituted with the supplied parameters
 * to mint concrete, validated {@link AgentSpec}s (e.g. one support agent per
 * customer tier or language).
 *
 * <p>
 * Substitution is literal text replacement: a parameter value is never
 * re-scanned for placeholders, so values cannot inject further substitutions.
 */
public final class AgentTemplate {

    private static final Pattern PLACEHOLDER = Pattern.compile("\\$\\{([A-Za-z][A-Za-z0-9_]*)}");

    private final Map<String, Object> definition;
    private final Set<String> parameters;
    private final Map<String, String> defaults;

    public AgentTemplate(Map<String, Object> definition, Map<String, String> defaults) {
        this.definition = definition;
        this.defaults = Map.copyOf(defaults == null ? Map.of() : defaults);
        Set<String> found = new LinkedHashSet<>();
        scan(definition, found);
        this.parameters = found;
    }

    /** Placeholders the template uses. */
    public Set<String> parameters() {
        return parameters;
    }

    public AgentSpec instantiate(Map<String, String> values) {
        List<String> missing = new ArrayList<>();
        for (String p : parameters) {
            if (!values.containsKey(p) && !defaults.containsKey(p)) {
                missing.add("missing template parameter '" + p + "'");
            }
        }
        if (!missing.isEmpty()) {
            throw new AgentSpecException(String.join("; ", missing));
        }
        Map<String, String> effective = new LinkedHashMap<>(defaults);
        effective.putAll(values);
        @SuppressWarnings("unchecked")
        Map<String, Object> filled = (Map<String, Object>) substitute(definition, effective);
        return AgentSpecLoader.fromMap(filled);
    }

    private static void scan(Object o, Set<String> out) {
        if (o instanceof String s) {
            Matcher m = PLACEHOLDER.matcher(s);
            while (m.find()) {
                out.add(m.group(1));
            }
        } else if (o instanceof Map<?, ?> m) {
            m.values().forEach(v -> scan(v, out));
        } else if (o instanceof List<?> l) {
            l.forEach(v -> scan(v, out));
        }
    }

    private static Object substitute(Object o, Map<String, String> values) {
        if (o instanceof String s) {
            Matcher m = PLACEHOLDER.matcher(s);
            StringBuilder sb = new StringBuilder();
            while (m.find()) {
                m.appendReplacement(sb, Matcher.quoteReplacement(values.get(m.group(1))));
            }
            m.appendTail(sb);
            return sb.toString();
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), substitute(v, values)));
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(substitute(v, values)));
            return out;
        }
        return o;
    }
}
