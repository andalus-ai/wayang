package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Set;
import org.junit.jupiter.api.Test;

class PluginRegistryTest {
    private static ComponentDescriptor component(String id) {
        return new ComponentDescriptor(id, "1.0.0", ComponentDescriptor.Kind.NODE, "wayang-eip", id,
                Set.of(), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of());
    }
    private static PluginRegistry.PluginPackage plugin(String id, String version,
            List<PluginManifest.Dependency> dependencies, String componentId) {
        var manifest = new PluginManifest(id, version, "1.0", "test", "*",
                List.of(new PluginManifest.ProvidedComponent(componentId, "1.0.0")),
                dependencies, Set.of(), "Apache-2.0");
        return new PluginRegistry.PluginPackage(manifest, List.of(component(componentId)));
    }

    @Test void resolvesRequiredDependenciesDependencyFirst() {
        var core = plugin("wayang.plugin.core", "1.4.0", List.of(), "wayang.core.base");
        var docs = plugin("wayang.plugin.documents", "1.2.0",
                List.of(new PluginManifest.Dependency("wayang.plugin.core", "^1.2.0", false)),
                "wayang.documents.extract");
        var registry = PluginRegistry.of(List.of(docs, core));
        assertEquals(List.of("wayang.plugin.core", "wayang.plugin.documents"),
                registry.resolve(List.of("wayang.plugin.documents")).stream().map(p -> p.manifest().id()).toList());
        assertEquals("wayang.plugin.documents", registry.ownerOf("wayang.documents.extract"));
    }

    @Test void rejectsMissingAndIncompatibleRequiredDependencies() {
        var dependent = plugin("wayang.plugin.dependent", "1.0.0",
                List.of(new PluginManifest.Dependency("wayang.plugin.core", "^2.0.0", false)),
                "wayang.dependent.node");
        assertTrue(assertThrows(PluginRegistry.PluginResolutionException.class,
                () -> PluginRegistry.of(List.of(dependent))).getMessage().contains("missing plugin"));
        var oldCore = plugin("wayang.plugin.core", "1.4.0", List.of(), "wayang.core.base");
        assertTrue(assertThrows(PluginRegistry.PluginResolutionException.class,
                () -> PluginRegistry.of(List.of(dependent, oldCore))).getMessage().contains("installed version"));
    }

    @Test void handlesOptionalDependencies() {
        var optional = new PluginManifest.Dependency("wayang.plugin.metrics", "^1.0.0", true);
        var app = plugin("wayang.plugin.app", "1.0.0", List.of(optional), "wayang.app.node");
        assertEquals(List.of("wayang.plugin.app"), PluginRegistry.of(List.of(app))
                .resolve(List.of("wayang.plugin.app")).stream().map(p -> p.manifest().id()).toList());
        var metrics = plugin("wayang.plugin.metrics", "1.3.0", List.of(), "wayang.metrics.node");
        assertEquals(List.of("wayang.plugin.metrics", "wayang.plugin.app"),
                PluginRegistry.of(List.of(app, metrics)).resolve(List.of("wayang.plugin.app"))
                        .stream().map(p -> p.manifest().id()).toList());
    }

    @Test void rejectsCyclesAndComponentOwnershipConflicts() {
        var a = plugin("wayang.plugin.alpha", "1.0.0",
                List.of(new PluginManifest.Dependency("wayang.plugin.beta", "*", false)), "wayang.alpha.node");
        var b = plugin("wayang.plugin.beta", "1.0.0",
                List.of(new PluginManifest.Dependency("wayang.plugin.alpha", "*", false)), "wayang.beta.node");
        var registry = PluginRegistry.of(List.of(a, b));
        assertTrue(assertThrows(PluginRegistry.PluginResolutionException.class,
                () -> registry.resolve(List.of("wayang.plugin.alpha"))).getMessage().contains("cycle"));
        var duplicate = plugin("wayang.plugin.other", "1.0.0", List.of(), "wayang.alpha.node");
        assertTrue(assertThrows(PluginRegistry.PluginResolutionException.class,
                () -> PluginRegistry.of(List.of(a, duplicate))).getMessage().contains("contributed by both"));
    }

    @Test void rejectsDuplicatePluginIdsAndUnknownRangeSyntax() {
        var one = plugin("wayang.plugin.same", "1.0.0", List.of(), "wayang.same.one");
        var two = plugin("wayang.plugin.same", "2.0.0", List.of(), "wayang.same.two");
        assertThrows(PluginRegistry.PluginResolutionException.class, () -> PluginRegistry.of(List.of(one, two)));
        assertThrows(PluginRegistry.PluginResolutionException.class,
                () -> PluginRegistry.VersionRange.matches("1.0.0", "latest"));
    }
}
