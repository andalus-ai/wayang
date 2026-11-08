package tech.kayys.wayang.runtime;

import java.util.LinkedHashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;

/**
 * Immutable, validated in-memory representation of a Wayang plugin manifest.
 * Parsing, signature verification, artifact installation, and dependency
 * resolution are separate responsibilities.
 */
public record PluginManifest(
        String id,
        String version,
        String apiVersion,
        String publisher,
        String runtimeCompatibility,
        List<ProvidedComponent> components,
        List<Dependency> dependencies,
        Set<String> permissions,
        String license) {

    public PluginManifest {
        id = requireText(id, "id");
        if (!id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")) {
            throw new IllegalArgumentException("Plugin id must be a namespaced lowercase id: " + id);
        }
        version = requireSemVer(version, "version");
        apiVersion = requireText(apiVersion, "apiVersion");
        if (!apiVersion.matches("[0-9]+\\.[0-9]+")) {
            throw new IllegalArgumentException("apiVersion must use MAJOR.MINOR format");
        }
        publisher = requireText(publisher, "publisher");
        runtimeCompatibility = requireText(runtimeCompatibility, "runtimeCompatibility");
        Objects.requireNonNull(components, "components");
        if (components.isEmpty()) {
            throw new IllegalArgumentException("A plugin must provide at least one component");
        }
        components = List.copyOf(components);
        Set<String> componentIds = new LinkedHashSet<>();
        for (ProvidedComponent component : components) {
            Objects.requireNonNull(component, "component");
            if (!componentIds.add(component.id())) {
                throw new IllegalArgumentException("Duplicate provided component '" + component.id() + "'");
            }
        }
        dependencies = dependencies == null ? List.of() : List.copyOf(dependencies);
        Set<String> dependencyIds = new LinkedHashSet<>();
        for (Dependency dependency : dependencies) {
            Objects.requireNonNull(dependency, "dependency");
            if (id.equals(dependency.id())) {
                throw new IllegalArgumentException("A plugin cannot depend on itself");
            }
            if (!dependencyIds.add(dependency.id())) {
                throw new IllegalArgumentException("Duplicate plugin dependency '" + dependency.id() + "'");
            }
        }
        permissions = permissions == null ? Set.of() : Set.copyOf(permissions);
        if (permissions.stream().anyMatch(permission -> !permission.matches("[a-z][a-z0-9.-]*:[A-Za-z0-9._*-]+"))) {
            throw new IllegalArgumentException("Plugin permissions must use the namespace:resource format");
        }
        license = license == null || license.isBlank() ? null : license.trim();
    }

    public record ProvidedComponent(String id, String version) {
        public ProvidedComponent {
            id = requireText(id, "component.id");
            if (!id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")) {
                throw new IllegalArgumentException("Component id must be a namespaced lowercase id: " + id);
            }
            version = requireSemVer(version, "component.version");
        }
    }

    /** Version range is retained as a constraint expression for a dedicated resolver. */
    public record Dependency(String id, String versionRange, boolean optional) {
        public Dependency {
            id = requireText(id, "dependency.id");
            if (!id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")) {
                throw new IllegalArgumentException("Dependency id must be a namespaced lowercase id: " + id);
            }
            versionRange = requireText(versionRange, "dependency.versionRange");
        }
    }

    private static String requireSemVer(String value, String field) {
        String text = requireText(value, field);
        if (!text.matches("(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)\\.(0|[1-9][0-9]*)(?:-[0-9A-Za-z.-]+)?(?:\\+[0-9A-Za-z.-]+)?")) {
            throw new IllegalArgumentException(field + " must be a semantic version: " + text);
        }
        return text;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) {
            throw new IllegalArgumentException(field + " must not be blank");
        }
        return value.trim();
    }
}
