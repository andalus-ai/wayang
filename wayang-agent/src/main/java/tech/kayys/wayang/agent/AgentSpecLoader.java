package tech.kayys.wayang.agent;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

/** Builds {@link AgentSpec}s from YAML (safe constructor: no arbitrary type instantiation) or Maps. */
public final class AgentSpecLoader {

    private AgentSpecLoader() {
    }

    public static AgentSpec fromYaml(String yaml) {
        LoaderOptions opts = new LoaderOptions();
        opts.setMaxAliasesForCollections(10);
        opts.setAllowDuplicateKeys(false);
        opts.setCodePointLimit(256 * 1024);
        Object doc;
        try {
            doc = new Yaml(new SafeConstructor(opts)).load(yaml);
        } catch (RuntimeException e) {
            throw new AgentSpecException("Invalid YAML: " + e.getMessage(), e);
        }
        if (!(doc instanceof Map<?, ?> m)) {
            throw new AgentSpecException("Agent YAML must be a mapping");
        }
        return fromMap(strKeys(m));
    }

    @SuppressWarnings("unchecked")
    public static AgentSpec fromMap(Map<String, ?> m) {
        List<AgentSpec.Tool> tools = new ArrayList<>();
        Object t = m.get("tools");
        if (t != null && !(t instanceof List)) {
            throw new AgentSpecException("'tools' must be a list");
        }
        for (Object o : t == null ? List.of() : (List<?>) t) {
            if (!(o instanceof Map<?, ?> tm)) {
                throw new AgentSpecException("Each tool must be an object");
            }
            Map<String, Object> tmap = strKeys(tm);
            Object cfg = tmap.get("config");
            tools.add(new AgentSpec.Tool(str(tmap.get("name")), str(tmap.get("type")), str(tmap.get("description")),
                    cfg instanceof Map<?, ?> cm ? strKeys(cm) : Map.of()));
        }
        Object opt = m.get("options");
        return new AgentSpec(str(m.get("id")), str(m.get("name")), str(m.get("model")), str(m.get("systemPrompt")),
                str(m.get("inputTemplate")), tools, intOr(m.get("maxSteps"), 10), intOr(m.get("timeoutSeconds"), 120),
                strings(m.get("requireApproval")), strings(m.get("outputKeys")),
                opt instanceof Map<?, ?> om ? strKeys(om) : Map.of());
    }

    private static Map<String, Object> strKeys(Map<?, ?> m) {
        Map<String, Object> out = new LinkedHashMap<>();
        m.forEach((k, v) -> out.put(String.valueOf(k), v));
        return out;
    }

    private static String str(Object o) {
        return o == null ? null : String.valueOf(o);
    }

    private static int intOr(Object o, int dflt) {
        if (o == null) {
            return dflt;
        }
        if (o instanceof Number n) {
            return n.intValue();
        }
        try {
            return new BigDecimal(String.valueOf(o)).intValueExact();
        } catch (ArithmeticException | NumberFormatException e) {
            throw new AgentSpecException("Expected an integer but got '" + o + "'");
        }
    }

    private static List<String> strings(Object o) {
        if (o == null) {
            return List.of();
        }
        if (!(o instanceof List<?> l)) {
            throw new AgentSpecException("Expected a list but got '" + o + "'");
        }
        List<String> out = new ArrayList<>();
        l.forEach(e -> out.add(String.valueOf(e)));
        return out;
    }
}
