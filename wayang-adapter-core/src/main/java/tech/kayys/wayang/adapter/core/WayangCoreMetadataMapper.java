package tech.kayys.wayang.adapter.core;

import java.util.*;
import java.util.stream.Collectors;
import tech.kayys.wayang.core.model.*;
import tech.kayys.wayang.runtime.ComponentExecutionCapabilities;
import tech.kayys.wayang.runtime.ComponentExecutionMode;
import tech.kayys.wayang.runtime.ComponentStateModel;
import tech.kayys.wayang.runtime.PluginManifest;

/**
 * Read-only metadata projection from the Wayang runtime SPI to Wayang Core.
 * This class does not load, install, activate, authorize, or execute plugin code.
 */
public final class WayangCoreMetadataMapper {
    public Projection map(PluginManifest manifest,
            List<tech.kayys.wayang.runtime.ComponentDescriptor> descriptors,
            List<ComponentExecutionCapabilities> executionDeclarations) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(descriptors, "descriptors");
        Objects.requireNonNull(executionDeclarations, "executionDeclarations");

        Map<String, tech.kayys.wayang.runtime.ComponentDescriptor> byId = new HashMap<>();
        for (var descriptor : descriptors)
            if (byId.putIfAbsent(descriptor.id(), descriptor) != null)
                throw new IllegalArgumentException("Duplicate component descriptor: " + descriptor.id());
        Map<String, ComponentExecutionCapabilities> executionById = new HashMap<>();
        for (var declaration : executionDeclarations)
            if (executionById.putIfAbsent(declaration.componentId(), declaration) != null)
                throw new IllegalArgumentException("Duplicate execution declaration: " + declaration.componentId());

        List<String> diagnostics = new ArrayList<>();
        List<tech.kayys.wayang.core.model.ComponentDescriptor> components = new ArrayList<>();
        for (PluginManifest.ProvidedComponent provided : manifest.components()) {
            var legacy = byId.get(provided.id());
            if (legacy == null) throw new IllegalArgumentException("Missing component descriptor: " + provided.id());
            if (!legacy.version().equals(provided.version()))
                throw new IllegalArgumentException("Manifest/descriptor version mismatch: " + provided.id());
            var declaration = executionById.get(provided.id());
            if (declaration == null)
                throw new IllegalArgumentException("Missing explicit execution declaration: " + provided.id());
            if (!declaration.componentVersion().equals(provided.version()))
                throw new IllegalArgumentException("Execution declaration version mismatch: " + provided.id());

            Map<String, String> metadata = new TreeMap<>();
            metadata.put("wayang:executor-type", legacy.executorType());
            metadata.put("wayang:category", legacy.category());
            metadata.put("wayang:required-features", sorted(legacy.requiredFeatures()));
            metadata.put("wayang:component-dependencies", legacy.dependencies().stream()
                    .map(d -> d.componentId() + "@" + d.version()).sorted().collect(Collectors.joining(",")));
            metadata.put("wayang:isolation-level", legacy.isolationLevel().name());
            metadata.put("wayang:capabilities", sorted(legacy.capabilities()));
            metadata.put("wayang:config-schema", legacy.configSchema().toString());
            metadata.put("wayang:ui-hints", legacy.uiHints().toString());
            if (legacy.icon() != null) metadata.put("wayang:icon", legacy.icon());

            components.add(new tech.kayys.wayang.core.model.ComponentDescriptor(
                    ComponentId.of(legacy.id()), SemanticVersion.parse(legacy.version()),
                    new ComponentKind(legacy.kind().name().toLowerCase(Locale.ROOT)),
                    legacy.displayName(), legacy.description(), List.of(), List.of(), null,
                    mapExecution(declaration), metadata));
            diagnostics.add(legacy.id() + ": legacy UI/config schema is retained as metadata; Core ports and schema refs are not inferred.");
            if (!legacy.dependencies().isEmpty())
                diagnostics.add(legacy.id() + ": component dependencies remain component metadata, not plugin dependencies.");
        }

        List<PluginDependency> dependencies = manifest.dependencies().stream()
                .map(d -> new PluginDependency(PluginId.of(d.id()), d.versionRange(), d.optional())).toList();
        Map<String, String> metadata = Map.of(
                "wayang:api-version", manifest.apiVersion(),
                "wayang:runtime-compatibility", manifest.runtimeCompatibility());
        var projected = new PluginDescriptor(PluginId.of(manifest.id()),
                SemanticVersion.parse(manifest.version()), manifest.publisher(), manifest.id(),
                null, manifest.license(), apiRange(manifest.apiVersion()),
                manifest.runtimeCompatibility(), components, dependencies,
                manifest.permissions(), null, metadata);
        diagnostics.add("Wayang manifest has no artifact digest field; digest was not fabricated.");
        return new Projection(projected, diagnostics);
    }

    private static tech.kayys.wayang.core.model.ComponentExecutionCapabilities mapExecution(
            tech.kayys.wayang.runtime.ComponentExecutionCapabilities source) {
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
        return new tech.kayys.wayang.core.model.ComponentExecutionCapabilities(modes, state, source.portablePayloads(),
                source.supportsCancellation(), source.idempotent());
    }

    private static String apiRange(String apiVersion) {
        String[] parts = apiVersion.split("\\.");
        int major = Integer.parseInt(parts[0]);
        int minor = Integer.parseInt(parts[1]);
        return ">=" + major + "." + minor + " <" + (major + 1) + ".0";
    }
    private static String sorted(Collection<?> values) {
        return values.stream().map(String::valueOf).sorted().collect(Collectors.joining(","));
    }

    public record Projection(PluginDescriptor descriptor, List<String> diagnostics) {
        public Projection {
            Objects.requireNonNull(descriptor, "descriptor");
            diagnostics = List.copyOf(diagnostics);
        }
    }
}