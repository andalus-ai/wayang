package tech.kayys.wayang.bpmn;

import java.util.Map;

/** A node of a process model. */
public record Element(String id, String name, ElementType type, Map<String, Object> config) {
    public Element {
        config = config == null ? Map.of() : Map.copyOf(config);
        if (name == null) {
            name = id;
        }
    }
}
