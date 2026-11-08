package tech.kayys.wayang.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Validated execution-capability catalog for a component registry.
 * Components without declarations retain legacy embedded behavior; missing metadata
 * never implies remote capability.
 */
public final class ComponentExecutionCapabilityRegistry {

    private final Map<String, ComponentExecutionCapabilities> capabilities;

    private ComponentExecutionCapabilityRegistry(Map<String, ComponentExecutionCapabilities> capabilities) {
        this.capabilities = Map.copyOf(capabilities);
    }

    public static ComponentExecutionCapabilityRegistry empty() {
        return new ComponentExecutionCapabilityRegistry(Map.of());
    }

    public static ComponentExecutionCapabilityRegistry of(
            ComponentRegistry components,
            Collection<ComponentExecutionCapabilities> declarations) {
        Objects.requireNonNull(components, "components");
        Objects.requireNonNull(declarations, "declarations");
        Map<String, ComponentExecutionCapabilities> indexed = new LinkedHashMap<>();
        for (ComponentExecutionCapabilities declaration : declarations) {
            Objects.requireNonNull(declaration, "declaration");
            ComponentDescriptor descriptor = components.find(declaration.componentId());
            if (descriptor == null) {
                throw new IllegalArgumentException("Execution capabilities refer to unavailable component '"
                        + declaration.componentId() + "'");
            }
            if (!descriptor.version().equals(declaration.componentVersion())) {
                throw new IllegalArgumentException("Execution capability version mismatch for '"
                        + declaration.componentId() + "': descriptor=" + descriptor.version()
                        + ", capabilities=" + declaration.componentVersion());
            }
            if (indexed.putIfAbsent(declaration.componentId(), declaration) != null) {
                throw new IllegalArgumentException("Duplicate execution capabilities for component '"
                        + declaration.componentId() + "'");
            }
        }
        return new ComponentExecutionCapabilityRegistry(indexed);
    }

    public ComponentExecutionCapabilities find(String componentId) {
        return capabilities.get(componentId);
    }

    public Map<String, ComponentExecutionCapabilities> all() {
        return capabilities;
    }

    public boolean supports(String componentId, ComponentExecutionMode mode) {
        ComponentExecutionCapabilities declaration = capabilities.get(componentId);
        return declaration != null && declaration.supportedModes().contains(mode);
    }
}
