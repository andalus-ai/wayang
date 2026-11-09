package tech.kayys.wayang.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/** Immutable, engine-neutral metadata for one deployable Wayang component. */
public record ComponentDescriptor(
        String id, String version, Kind kind, String executorType, String description,
        Set<Profile.Feature> requiredFeatures, List<Dependency> dependencies,
        IsolationLevel isolationLevel, Set<String> capabilities,
        String displayName, String category, String icon,
        Map<String, Object> configSchema, Map<String, Object> uiHints) {

    /** Source-compatible constructor for extensions compiled against the original SPI. */
    public ComponentDescriptor(String id, String version, Kind kind, String executorType, String description,
            Set<Profile.Feature> requiredFeatures, List<Dependency> dependencies,
            IsolationLevel isolationLevel, Set<String> capabilities) {
        this(id, version, kind, executorType, description, requiredFeatures, dependencies, isolationLevel,
                capabilities, id, kind.name(), null,
                Map.of("type", "object", "properties", Map.of(), "additionalProperties", false), Map.of());
    }

    public ComponentDescriptor {
        id = requireText(id, "id");
        if (!id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+"))
            throw new IllegalArgumentException("Component id must be a namespaced lowercase id: " + id);
        version = requireText(version, "version");
        kind = Objects.requireNonNull(kind, "kind");
        executorType = requireText(executorType, "executorType");
        description = description == null ? "" : description;
        requiredFeatures = requiredFeatures == null || requiredFeatures.isEmpty() ? Set.of() : Set.copyOf(requiredFeatures);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        isolationLevel = Objects.requireNonNull(isolationLevel, "isolationLevel");
        capabilities = capabilities == null || capabilities.isEmpty() ? Set.of() : Set.copyOf(capabilities);
        displayName = displayName == null || displayName.isBlank() ? id : displayName.trim();
        category = category == null || category.isBlank() ? kind.name() : category.trim();
        icon = icon == null || icon.isBlank() ? null : icon.trim();
        configSchema = immutableMap(configSchema == null || configSchema.isEmpty()
                ? Map.of("type", "object", "properties", Map.of(), "additionalProperties", false) : configSchema);
        uiHints = immutableMap(uiHints == null ? Map.of() : uiHints);
        if (!"object".equals(configSchema.get("type")))
            throw new IllegalArgumentException("Component configSchema root must have type 'object'");
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
    private static Map<String, Object> immutableMap(Map<String, ?> source) {
        Map<String, Object> copy = new LinkedHashMap<>();
        source.forEach((key, value) -> copy.put(key, immutableValue(value)));
        return Collections.unmodifiableMap(copy);
    }
    private static Object immutableValue(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> copy = new LinkedHashMap<>();
            map.forEach((k, v) -> copy.put(String.valueOf(k), immutableValue(v)));
            return Collections.unmodifiableMap(copy);
        }
        if (value instanceof List<?> list) return list.stream().map(ComponentDescriptor::immutableValue).toList();
        if (value instanceof Set<?> set) return set.stream().map(ComponentDescriptor::immutableValue).toList();
        return value;
    }

    public enum Kind { TRIGGER, NODE, CONNECTOR, DECISION, AGENT, HUMAN_TASK, TRANSFORMER, OBSERVER }
    public record Dependency(String componentId, String version) {
        public Dependency {
            componentId = requireText(componentId, "dependency.componentId");
            version = requireText(version, "dependency.version");
        }
    }
    public enum IsolationLevel { IN_PROCESS, SEPARATE_PROCESS, CONTAINER, REMOTE }
}
