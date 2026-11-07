package tech.kayys.wayang.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/**
 * Validated, profile-scoped pairing of component metadata and executable handlers.
 *
 * <p>The component registry remains the source of truth for availability and
 * dependency closure. This catalog adds the runtime binding check: every
 * resolved component must have exactly one executor whose family and version
 * agree with its descriptor. It does not perform class loading or artifact
 * installation.</p>
 */
public final class ComponentExecutorCatalog {

    private final Profile profile;
    private final List<ComponentDescriptor> components;
    private final ComponentExecutorRegistry executors;

    private ComponentExecutorCatalog(Profile profile, List<ComponentDescriptor> components,
            ComponentExecutorRegistry executors) {
        this.profile = profile;
        this.components = List.copyOf(components);
        this.executors = executors;
    }

    /**
     * Resolve roots and bind executors. Extra bindings are rejected rather than
     * silently making profile-disabled or unselected code executable.
     */
    public static ComponentExecutorCatalog resolve(ComponentRegistry registry,
            Collection<String> requestedComponentIds,
            Collection<ComponentExecutorBinding> bindings) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(requestedComponentIds, "requestedComponentIds");
        Objects.requireNonNull(bindings, "bindings");

        List<ComponentDescriptor> closure = registry.resolve(requestedComponentIds);
        Map<String, ComponentDescriptor> descriptors = new LinkedHashMap<>();
        closure.forEach(descriptor -> descriptors.put(descriptor.id(), descriptor));

        Map<String, ComponentExecutor> byId = new LinkedHashMap<>();
        for (ComponentExecutorBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            ComponentDescriptor descriptor = descriptors.get(binding.componentId());
            if (descriptor == null) {
                throw new IllegalArgumentException("Executor binding is not in the resolved component closure: "
                        + binding.componentId());
            }
            ComponentExecutor executor = binding.executor();
            if (!descriptor.version().equals(binding.componentVersion())) {
                throw new IllegalArgumentException("Executor version mismatch for '" + binding.componentId()
                        + "': descriptor=" + descriptor.version() + ", executor=" + binding.componentVersion());
            }
            if (!descriptor.executorType().equals(executor.executorId())) {
                throw new IllegalArgumentException("Executor family mismatch for '" + binding.componentId()
                        + "': descriptor=" + descriptor.executorType() + ", executor=" + executor.executorId());
            }
            if (byId.putIfAbsent(binding.componentId(), executor) != null) {
                throw new IllegalArgumentException("Duplicate executor binding for component '"
                        + binding.componentId() + "'");
            }
        }

        List<String> missing = descriptors.keySet().stream().filter(id -> !byId.containsKey(id)).toList();
        if (!missing.isEmpty()) {
            throw new IllegalArgumentException("Missing executors for resolved components: " + missing);
        }
        return new ComponentExecutorCatalog(registry.profile(), closure, ComponentExecutorRegistry.of(byId));
    }

    public Profile profile() {
        return profile;
    }

    /** Dependency-first immutable descriptor closure. */
    public List<ComponentDescriptor> components() {
        return components;
    }

    public ComponentExecutorRegistry executors() {
        return executors;
    }

    public List<String> componentIds() {
        return components.stream().map(ComponentDescriptor::id).toList();
    }

    /** Explicit binding between one component/version and its implementation. */
    public record ComponentExecutorBinding(String componentId, String componentVersion,
            ComponentExecutor executor) {
        public ComponentExecutorBinding {
            if (componentId == null || componentId.isBlank())
                throw new IllegalArgumentException("componentId must not be blank");
            if (componentVersion == null || componentVersion.isBlank())
                throw new IllegalArgumentException("componentVersion must not be blank");
            Objects.requireNonNull(executor, "executor");
        }
    }
}
