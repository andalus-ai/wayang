package tech.kayys.wayang.adapter.core;

import java.util.*;
import java.util.stream.Collectors;

import tech.kayys.wayang.core.model.PluginDescriptor;
import tech.kayys.wayang.runtime.ComponentExecutionCapabilities;
import tech.kayys.wayang.runtime.PluginRegistry;

/**
 * Read-only bridge from Wayang's validated plugin package registry to Core descriptors.
 * This is a metadata view only: it does not activate or install plugin packages.
 */
public final class WayangCoreMetadataCatalog {
    private final WayangCoreMetadataMapper mapper;

    public WayangCoreMetadataCatalog() {
        this(new WayangCoreMetadataMapper());
    }

    public WayangCoreMetadataCatalog(WayangCoreMetadataMapper mapper) {
        this.mapper = Objects.requireNonNull(mapper, "mapper");
    }

    /**
     * Projects every package in deterministic plugin-ID order. Execution declarations
     * must cover every component exactly once; missing declarations are never guessed.
     */
    public CatalogProjection projectAll(PluginRegistry registry,
            Collection<ComponentExecutionCapabilities> declarations) {
        Objects.requireNonNull(registry, "registry");
        Objects.requireNonNull(declarations, "declarations");
        Map<String, ComponentExecutionCapabilities> indexed = index(declarations);
        List<PluginDescriptor> plugins = new ArrayList<>();
        List<WayangCoreMetadataMapper.Diagnostic> diagnostics = new ArrayList<>();
        Set<String> consumed = new HashSet<>();

        for (PluginRegistry.PluginPackage plugin : registry.all()) {
            Set<String> componentIds = plugin.components().stream()
                    .map(tech.kayys.wayang.runtime.ComponentDescriptor::id).collect(Collectors.toSet());
            List<ComponentExecutionCapabilities> pluginDeclarations = new ArrayList<>();
            for (String componentId : componentIds) {
                ComponentExecutionCapabilities declaration = indexed.get(componentId);
                if (declaration != null) {
                    pluginDeclarations.add(declaration);
                    consumed.add(componentId);
                }
            }
            var projection = mapper.map(plugin.manifest(), plugin.components(), pluginDeclarations);
            plugins.add(projection.descriptor());
            diagnostics.addAll(projection.diagnostics());
        }

        Set<String> unknown = new TreeSet<>(indexed.keySet());
        unknown.removeAll(consumed);
        if (!unknown.isEmpty())
            throw new IllegalArgumentException("Execution declarations refer to components outside the plugin registry: " + unknown);
        return new CatalogProjection(plugins, diagnostics);
    }

    private static Map<String, ComponentExecutionCapabilities> index(
            Collection<ComponentExecutionCapabilities> declarations) {
        Map<String, ComponentExecutionCapabilities> indexed = new HashMap<>();
        for (ComponentExecutionCapabilities declaration : declarations) {
            Objects.requireNonNull(declaration, "declaration");
            if (indexed.putIfAbsent(declaration.componentId(), declaration) != null)
                throw new IllegalArgumentException("Duplicate execution declaration: " + declaration.componentId());
        }
        return indexed;
    }

    public record CatalogProjection(List<PluginDescriptor> plugins,
            List<WayangCoreMetadataMapper.Diagnostic> diagnostics) {
        public CatalogProjection {
            plugins = List.copyOf(plugins);
            diagnostics = List.copyOf(diagnostics);
        }
        public Optional<PluginDescriptor> find(String pluginId) {
            return plugins.stream().filter(plugin -> plugin.id().toString().equals(pluginId)).findFirst();
        }
        public boolean hasWarnings() {
            return diagnostics.stream().anyMatch(d -> d.severity() == WayangCoreMetadataMapper.Severity.WARNING);
        }
    }
}
