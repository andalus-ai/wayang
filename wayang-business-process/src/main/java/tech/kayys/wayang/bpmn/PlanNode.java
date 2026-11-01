package tech.kayys.wayang.bpmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * One node of the compiled execution plan. Fully round-trips through a plain
 * Map ({@link #toConfiguration()} / {@link #fromConfiguration(Map)}) so that it
 * can travel inside a workflow node's configuration and be rebuilt by the
 * executor without any shared state.
 */
public record PlanNode(String id, String name, ElementType type, String executorType, Map<String, Object> config,
        List<Incoming> incoming, List<Outgoing> outgoing) {

    public record Incoming(String flowId, String sourceId) {
    }

    public record Outgoing(String flowId, String targetId, String condition, boolean isDefault) {
    }

    public PlanNode {
        config = config == null ? Map.of() : Map.copyOf(config);
        incoming = List.copyOf(incoming);
        outgoing = List.copyOf(outgoing);
    }

    /** Ids of the nodes that must complete before this one. */
    public List<String> dependsOn() {
        return incoming.stream().map(Incoming::sourceId).distinct().toList();
    }

    public Map<String, Object> toConfiguration() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("wayang.element.id", id);
        m.put("wayang.element.name", name);
        m.put("wayang.element.type", type.name());
        m.put("wayang.element.config", new LinkedHashMap<>(config));
        List<Object> in = new ArrayList<>();
        for (Incoming i : incoming) {
            in.add(Map.of("flow", i.flowId(), "source", i.sourceId()));
        }
        m.put("wayang.element.incoming", in);
        List<Object> out = new ArrayList<>();
        for (Outgoing o : outgoing) {
            Map<String, Object> om = new LinkedHashMap<>();
            om.put("flow", o.flowId());
            om.put("target", o.targetId());
            if (o.condition() != null) {
                om.put("condition", o.condition());
            }
            om.put("default", o.isDefault());
            out.add(om);
        }
        m.put("wayang.element.outgoing", out);
        return m;
    }

    @SuppressWarnings("unchecked")
    public static PlanNode fromConfiguration(Map<String, ?> m) {
        Object id = m.get("wayang.element.id");
        Object type = m.get("wayang.element.type");
        if (id == null || type == null) {
            throw new IllegalArgumentException("Not a Wayang plan node: missing wayang.element.* configuration");
        }
        ElementType t = ElementType.valueOf(String.valueOf(type));
        List<Incoming> in = new ArrayList<>();
        for (Object o : (List<Object>) (List<?>) listOf(m.get("wayang.element.incoming"))) {
            Map<String, Object> im = (Map<String, Object>) o;
            in.add(new Incoming(String.valueOf(im.get("flow")), String.valueOf(im.get("source"))));
        }
        List<Outgoing> out = new ArrayList<>();
        for (Object o : (List<Object>) (List<?>) listOf(m.get("wayang.element.outgoing"))) {
            Map<String, Object> om = (Map<String, Object>) o;
            out.add(new Outgoing(String.valueOf(om.get("flow")), String.valueOf(om.get("target")),
                    om.get("condition") == null ? null : String.valueOf(om.get("condition")),
                    Boolean.TRUE.equals(om.get("default"))));
        }
        Object name = m.get("wayang.element.name");
        Object cfg = m.get("wayang.element.config");
        Map<String, Object> config = cfg instanceof Map<?, ?> cm ? (Map<String, Object>) cm : Map.of();
        String exec = config.get("executorType") instanceof String s ? s : t.defaultExecutorType();
        return new PlanNode(String.valueOf(id), name == null ? String.valueOf(id) : String.valueOf(name), t, exec, config,
                in, out);
    }

    private static List<?> listOf(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }
}
