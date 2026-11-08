package tech.kayys.wayang.adapter.core;

import java.math.BigDecimal;
import java.util.*;
import java.util.stream.Collectors;

import tech.kayys.wayang.core.model.*;
import tech.kayys.wayang.runtime.ComponentExecutionCapabilities;
import tech.kayys.wayang.runtime.ComponentExecutionMode;
import tech.kayys.wayang.runtime.ComponentStateModel;
import tech.kayys.wayang.runtime.PluginManifest;

/**
 * Deterministic, read-only projection from the Wayang runtime SPI to Wayang Core.
 * Unsupported Wayang fields are retained in namespaced JSON metadata or reported
 * as diagnostics. This mapper never loads, installs, activates, or authorizes code.
 */
public final class WayangCoreMetadataMapper {
    private static final String PREFIX = "wayang:";

    public Projection map(PluginManifest manifest,
            List<tech.kayys.wayang.runtime.ComponentDescriptor> descriptors,
            List<ComponentExecutionCapabilities> executionDeclarations) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(descriptors, "descriptors");
        Objects.requireNonNull(executionDeclarations, "executionDeclarations");

        Map<String, tech.kayys.wayang.runtime.ComponentDescriptor> byId = indexDescriptors(descriptors);
        Map<String, ComponentExecutionCapabilities> executionById = indexExecution(executionDeclarations);
        Set<String> manifestIds = manifest.components().stream()
                .map(PluginManifest.ProvidedComponent::id).collect(Collectors.toCollection(TreeSet::new));
        if (manifestIds.size() != manifest.components().size())
            throw new IllegalArgumentException("Manifest contains duplicate component IDs");
        requireExactKeys("component descriptors", manifestIds, byId.keySet());
        requireExactKeys("execution declarations", manifestIds, executionById.keySet());

        List<Diagnostic> diagnostics = new ArrayList<>();
        List<tech.kayys.wayang.core.model.ComponentDescriptor> components = new ArrayList<>();
        for (PluginManifest.ProvidedComponent provided : manifest.components()) {
            var legacy = byId.get(provided.id());
            if (!legacy.version().equals(provided.version()))
                throw new IllegalArgumentException("Manifest/descriptor version mismatch: " + provided.id());
            var declaration = executionById.get(provided.id());
            if (!declaration.componentVersion().equals(provided.version()))
                throw new IllegalArgumentException("Execution declaration version mismatch: " + provided.id());

            Map<String, String> metadata = new TreeMap<>();
            metadata.put(PREFIX + "executor-type", legacy.executorType());
            metadata.put(PREFIX + "category", legacy.category());
            metadata.put(PREFIX + "required-features", json(legacy.requiredFeatures().stream()
                    .map(Enum::name).sorted().toList()));
            metadata.put(PREFIX + "component-dependencies", json(legacy.dependencies().stream()
                    .sorted(Comparator.comparing(tech.kayys.wayang.runtime.ComponentDescriptor.Dependency::componentId))
                    .map(d -> Map.of("componentId", d.componentId(), "version", d.version())).toList()));
            metadata.put(PREFIX + "isolation-level", legacy.isolationLevel().name());
            metadata.put(PREFIX + "capabilities", json(legacy.capabilities().stream().sorted().toList()));
            metadata.put(PREFIX + "config-schema", json(legacy.configSchema()));
            metadata.put(PREFIX + "ui-hints", json(legacy.uiHints()));
            if (legacy.icon() != null) metadata.put(PREFIX + "icon", legacy.icon());

            components.add(new tech.kayys.wayang.core.model.ComponentDescriptor(
                    ComponentId.of(legacy.id()), SemanticVersion.parse(legacy.version()),
                    new ComponentKind(legacy.kind().name().toLowerCase(Locale.ROOT)),
                    legacy.displayName(), legacy.description(), List.of(), List.of(), null,
                    mapExecution(declaration, metadata), metadata));
            diagnostics.add(Diagnostic.warning("component." + legacy.id() + ".configSchema",
                    "Wayang config schema is retained as JSON metadata; Core configurationSchemaRef is not inferred."));
            diagnostics.add(Diagnostic.warning("component." + legacy.id() + ".ports",
                    "Wayang component descriptors do not declare typed Core ports; no ports were inferred."));
        }

        List<PluginDependency> dependencies = manifest.dependencies().stream()
                .map(d -> new PluginDependency(PluginId.of(d.id()), d.versionRange(), d.optional())).toList();
        Map<String, String> pluginMetadata = new TreeMap<>();
        pluginMetadata.put(PREFIX + "api-version", manifest.apiVersion());
        pluginMetadata.put(PREFIX + "runtime-compatibility", manifest.runtimeCompatibility());
        pluginMetadata.put(PREFIX + "permissions", json(manifest.permissions().stream().sorted().toList()));
        var projected = new PluginDescriptor(PluginId.of(manifest.id()),
                SemanticVersion.parse(manifest.version()), manifest.publisher(), manifest.id(),
                null, manifest.license(), apiRange(manifest.apiVersion()),
                manifest.runtimeCompatibility(), components, dependencies,
                manifest.permissions(), null, pluginMetadata);
        diagnostics.add(Diagnostic.warning("plugin.artifactDigest",
                "Wayang PluginManifest does not carry an artifact digest; digest remains unset."));
        diagnostics.add(Diagnostic.warning("plugin.displayName",
                "Wayang PluginManifest has no separate display name; plugin ID is used as the Core display name."));
        return new Projection(projected, diagnostics);
    }

    private static Map<String, tech.kayys.wayang.runtime.ComponentDescriptor> indexDescriptors(
            List<tech.kayys.wayang.runtime.ComponentDescriptor> values) {
        Map<String, tech.kayys.wayang.runtime.ComponentDescriptor> result = new HashMap<>();
        for (var value : values)
            if (result.putIfAbsent(value.id(), value) != null)
                throw new IllegalArgumentException("Duplicate component descriptor: " + value.id());
        return result;
    }

    private static Map<String, ComponentExecutionCapabilities> indexExecution(
            List<ComponentExecutionCapabilities> values) {
        Map<String, ComponentExecutionCapabilities> result = new HashMap<>();
        for (var value : values)
            if (result.putIfAbsent(value.componentId(), value) != null)
                throw new IllegalArgumentException("Duplicate execution declaration: " + value.componentId());
        return result;
    }

    private static void requireExactKeys(String label, Set<String> expected, Set<String> actual) {
        if (expected.equals(actual)) return;
        Set<String> missing = new TreeSet<>(expected); missing.removeAll(actual);
        Set<String> unexpected = new TreeSet<>(actual); unexpected.removeAll(expected);
        throw new IllegalArgumentException(label + " do not match manifest; missing=" + missing + ", unexpected=" + unexpected);
    }

    private static tech.kayys.wayang.core.model.ComponentExecutionCapabilities mapExecution(
            ComponentExecutionCapabilities source, Map<String, String> metadata) {
        Set<ExecutionMode> modes = new HashSet<>();
        for (ComponentExecutionMode mode : source.supportedModes()) {
            modes.add(switch (mode) {
                case EMBEDDED -> ExecutionMode.EMBEDDED;
                case ISOLATED_LOCAL -> ExecutionMode.ISOLATED_LOCAL;
                case REMOTE -> ExecutionMode.REMOTE;
                case CLOUD_NATIVE -> ExecutionMode.CLOUD_NATIVE;
            });
        }
        StateModel state = switch (source.stateModel()) {
            case STATELESS -> StateModel.STATELESS;
            case EXTERNALIZED -> StateModel.EXTERNALIZED;
            case AFFINE -> StateModel.AFFINE;
        };
        if (source.affinityKey() != null) metadata.put(PREFIX + "affinity-key", source.affinityKey());
        metadata.put(PREFIX + "required-capabilities", json(source.requiredCapabilities().stream().sorted().toList()));
        metadata.put(PREFIX + "resource-hints", json(source.resourceHints()));
        return new tech.kayys.wayang.core.model.ComponentExecutionCapabilities(modes, state,
                source.portablePayloads(), source.supportsCancellation(), source.idempotent());
    }

    private static String apiRange(String apiVersion) {
        String[] parts = apiVersion.split("\\.", -1);
        if (parts.length != 2 || !parts[0].matches("\\d+") || !parts[1].matches("\\d+"))
            throw new IllegalArgumentException("Expected plugin API version in major.minor form: " + apiVersion);
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        return ">=" + major + "." + minor + " <" + (major + 1) + ".0";
    }

    /** Small canonical JSON writer for the JSON-compatible values accepted by Wayang descriptors. */
    static String json(Object value) {
        if (value == null) return "null";
        if (value instanceof String text) return quote(text);
        if (value instanceof Boolean || value instanceof Integer || value instanceof Long
                || value instanceof Short || value instanceof Byte) return value.toString();
        if (value instanceof Float f) {
            if (!Float.isFinite(f)) throw new IllegalArgumentException("Non-finite JSON number");
            return new BigDecimal(f.toString()).stripTrailingZeros().toPlainString();
        }
        if (value instanceof Double d) {
            if (!Double.isFinite(d)) throw new IllegalArgumentException("Non-finite JSON number");
            return BigDecimal.valueOf(d).stripTrailingZeros().toPlainString();
        }
        if (value instanceof BigDecimal decimal) return decimal.stripTrailingZeros().toPlainString();
        if (value instanceof Map<?, ?> map) {
            List<String> keys = map.keySet().stream().map(String::valueOf).sorted().toList();
            return keys.stream().map(key -> quote(key) + ":" + json(map.get(key)))
                    .collect(Collectors.joining(",", "{", "}"));
        }
        if (value instanceof Collection<?> collection)
            return collection.stream().map(WayangCoreMetadataMapper::json).collect(Collectors.joining(",", "[", "]"));
        if (value.getClass().isArray()) {
            int length = java.lang.reflect.Array.getLength(value);
            List<String> elements = new ArrayList<>(length);
            for (int i = 0; i < length; i++) elements.add(json(java.lang.reflect.Array.get(value, i)));
            return String.join(",", elements).transform(s -> "[" + s + "]");
        }
        throw new IllegalArgumentException("Unsupported JSON metadata value type: " + value.getClass().getName());
    }

    private static String quote(String value) {
        StringBuilder out = new StringBuilder("\"");
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\b' -> out.append("\\b");
                case '\f' -> out.append("\\f");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> { if (c < 0x20) out.append(String.format("\\u%04x", (int)c)); else out.append(c); }
            }
        }
        return out.append('"').toString();
    }

    public enum Severity { INFO, WARNING, ERROR }
    public record Diagnostic(Severity severity, String fieldPath, String message) {
        public Diagnostic {
            Objects.requireNonNull(severity, "severity");
            if (fieldPath == null || fieldPath.isBlank()) throw new IllegalArgumentException("fieldPath must not be blank");
            if (message == null || message.isBlank()) throw new IllegalArgumentException("message must not be blank");
        }
        static Diagnostic warning(String path, String message) { return new Diagnostic(Severity.WARNING, path, message); }
    }
    public record Projection(PluginDescriptor descriptor, List<Diagnostic> diagnostics) {
        public Projection {
            Objects.requireNonNull(descriptor, "descriptor");
            diagnostics = List.copyOf(diagnostics);
        }
        public boolean hasWarnings() { return diagnostics.stream().anyMatch(d -> d.severity() == Severity.WARNING); }
    }
}
