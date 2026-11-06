package tech.kayys.wayang.runtime;

import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, engine-neutral metadata describing one deployable Wayang component.
 *
 * <p>The descriptor is deliberately metadata-only: it does not instantiate handlers,
 * load arbitrary classes, or execute plugin code. Runtime execution continues through
 * the existing {@link WayangExtension} and {@link DomainHandler} contracts.</p>
 */
public record ComponentDescriptor(
        String id,
        String version,
        Kind kind,
        String executorType,
        String description,
        Set<Profile.Feature> requiredFeatures,
        List<Dependency> dependencies,
        IsolationLevel isolationLevel,
        Set<String> capabilities) {

    public ComponentDescriptor {
        id = requireText(id, "id");
        if (!id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")) {
            throw new IllegalArgumentException("Component id must be a namespaced lowercase id: " + id);
        }
        version = requireText(version, "version");
        kind = Objects.requireNonNull(kind, "kind");
        executorType = requireText(executorType, "executorType");
        description = description == null ? "" : description;
        requiredFeatures = requiredFeatures == null || requiredFeatures.isEmpty()
                ? Set.of()
                : Set.copyOf(requiredFeatures);
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        isolationLevel = Objects.requireNonNull(isolationLevel, "isolationLevel");
        capabilities = capabilities == null || capabilities.isEmpty()
                ? Set.of()
                : Set.copyOf(capabilities);
    }

    private static String requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }

    public enum Kind {
        TRIGGER,
        NODE,
        CONNECTOR,
        DECISION,
        AGENT,
        HUMAN_TASK,
        TRANSFORMER,
        OBSERVER
    }

    /** Exact-version dependency for deterministic runtime resolution. */
    public record Dependency(String componentId, String version) {
        public Dependency {
            componentId = requireText(componentId, "dependency.componentId");
            version = requireText(version, "dependency.version");
        }
    }

    /**
     * Execution boundary required by this component. IN_PROCESS is suitable only
     * for trusted code; third-party isolation must be enforced by a separate runtime.
     */
    public enum IsolationLevel {
        IN_PROCESS,
        SEPARATE_PROCESS,
        CONTAINER,
        REMOTE
    }
}
