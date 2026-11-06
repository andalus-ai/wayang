package tech.kayys.wayang.runtime;

import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Read-only component catalog for one runtime profile.
 *
 * <p>The registry resolves a selected component set to its exact dependency closure.
 * It does not download artifacts or load plugin classes; those responsibilities belong
 * to the release compiler and the runtime's packaging/class-loading boundary.</p>
 */
public final class ComponentRegistry {

    private final Profile profile;
    private final Map<String, ComponentDescriptor> components;

    public ComponentRegistry(Collection<ComponentDescriptor> descriptors, Profile profile) {
        this.profile = Objects.requireNonNull(profile, "profile");
        Objects.requireNonNull(descriptors, "descriptors");

        Map<String, ComponentDescriptor> all = new LinkedHashMap<>();
        descriptors.stream()
                .filter(Objects::nonNull)
                .sorted(Comparator.comparing(ComponentDescriptor::id))
                .forEach(descriptor -> {
                    ComponentDescriptor previous = all.putIfAbsent(descriptor.id(), descriptor);
                    if (previous != null) {
                        throw new IllegalStateException("Duplicate component id '" + descriptor.id()
                                + "' (" + previous.version() + " and " + descriptor.version() + ")");
                    }
                });

        Map<String, ComponentDescriptor> enabled = new LinkedHashMap<>();
        all.forEach((id, descriptor) -> {
            if (profile.features().containsAll(descriptor.requiredFeatures())) {
                enabled.put(id, descriptor);
            }
        });
        this.components = Map.copyOf(enabled);
    }

    public static ComponentRegistry from(Extensions extensions, Profile profile) {
        Objects.requireNonNull(extensions, "extensions");
        return new ComponentRegistry(extensions.components(), profile);
    }

    public Profile profile() {
        return profile;
    }

    public List<ComponentDescriptor> all() {
        return components.values().stream()
                .sorted(Comparator.comparing(ComponentDescriptor::id))
                .toList();
    }

    public ComponentDescriptor find(String id) {
        return components.get(id);
    }

    public Set<String> ids() {
        return components.keySet();
    }

    /**
     * Resolve requested component IDs to a deterministic, dependency-first closure.
     *
     * @throws ComponentResolutionException if an ID is unavailable, a dependency is
     *         missing/incompatible, or the dependency graph contains a cycle
     */
    public List<ComponentDescriptor> resolve(Collection<String> requestedIds) {
        Objects.requireNonNull(requestedIds, "requestedIds");
        LinkedHashSet<String> ordered = new LinkedHashSet<>();
        Set<String> visiting = new LinkedHashSet<>();
        for (String id : requestedIds) {
            visit(requireId(id), ordered, visiting);
        }
        return ordered.stream().map(components::get).toList();
    }

    private void visit(String id, LinkedHashSet<String> ordered, Set<String> visiting) {
        if (ordered.contains(id)) {
            return;
        }
        ComponentDescriptor descriptor = components.get(id);
        if (descriptor == null) {
            throw new ComponentResolutionException("Component '" + id + "' is not available in profile '"
                    + profile.name() + "'");
        }
        if (!visiting.add(id)) {
            throw new ComponentResolutionException("Component dependency cycle detected: "
                    + String.join(" -> ", visiting) + " -> " + id);
        }
        for (ComponentDescriptor.Dependency dependency : descriptor.dependencies()) {
            ComponentDescriptor target = components.get(dependency.componentId());
            if (target == null) {
                throw new ComponentResolutionException("Component '" + id + "' requires unavailable component '"
                        + dependency.componentId() + "'@" + dependency.version());
            }
            if (!target.version().equals(dependency.version())) {
                throw new ComponentResolutionException("Component '" + id + "' requires '"
                        + dependency.componentId() + "'@" + dependency.version() + " but registry provides "
                        + target.version());
            }
            visit(dependency.componentId(), ordered, visiting);
        }
        visiting.remove(id);
        ordered.add(id);
    }

    private static String requireId(String id) {
        if (id == null || id.isBlank()) {
            throw new ComponentResolutionException("Requested component id must not be blank");
        }
        return id.trim();
    }

    public static final class ComponentResolutionException extends IllegalArgumentException {
        public ComponentResolutionException(String message) {
            super(message);
        }
    }
}
