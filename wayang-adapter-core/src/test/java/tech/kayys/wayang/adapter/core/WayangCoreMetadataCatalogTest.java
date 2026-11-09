package tech.kayys.wayang.adapter.core;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ComponentExecutionCapabilities;
import tech.kayys.wayang.runtime.ComponentExecutionMode;
import tech.kayys.wayang.runtime.ComponentStateModel;
import tech.kayys.wayang.runtime.PluginManifest;
import tech.kayys.wayang.runtime.PluginRegistry;

class WayangCoreMetadataCatalogTest {
    @Test
    void projectsValidatedRegistryInStablePluginOrder() {
        var alpha = plugin("acme.alpha", "acme.alpha.step");
        var beta = plugin("acme.beta", "acme.beta.step");
        var registry = PluginRegistry.of(List.of(beta, alpha));
        var declarations = List.of(execution("acme.beta.step"), execution("acme.alpha.step"));

        var result = new WayangCoreMetadataCatalog().projectAll(registry, declarations);

        assertEquals(List.of("acme.alpha", "acme.beta"),
                result.plugins().stream().map(p -> p.id().toString()).toList());
        assertTrue(result.find("acme.alpha").isPresent());
    }

    @Test
    void rejectsMissingDeclarationForAnyRegisteredComponent() {
        var registry = PluginRegistry.of(List.of(plugin("acme.alpha", "acme.alpha.step")));
        assertThrows(IllegalArgumentException.class,
                () -> new WayangCoreMetadataCatalog().projectAll(registry, List.of()));
    }

    @Test
    void rejectsDeclarationsOutsideRegistry() {
        var registry = PluginRegistry.of(List.of(plugin("acme.alpha", "acme.alpha.step")));
        assertThrows(IllegalArgumentException.class,
                () -> new WayangCoreMetadataCatalog().projectAll(registry,
                        List.of(execution("acme.other.step"), execution("acme.alpha.step"))));
    }

    private static PluginRegistry.PluginPackage plugin(String pluginId, String componentId) {
        var manifest = new PluginManifest(pluginId, "1.0.0", "1.0", "Acme", ">=1.0 <2.0",
                List.of(new PluginManifest.ProvidedComponent(componentId, "1.0.0")),
                List.of(), Set.of("component:execute"), "Apache-2.0");
        var descriptor = new ComponentDescriptor(componentId, "1.0.0", ComponentDescriptor.Kind.NODE,
                "test", "test", Set.of(), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS,
                Set.of("test"), componentId, "test", null,
                Map.of("type", "object", "properties", Map.of()), Map.of());
        return new PluginRegistry.PluginPackage(manifest, List.of(descriptor));
    }

    private static ComponentExecutionCapabilities execution(String componentId) {
        return new ComponentExecutionCapabilities(componentId, "1.0.0",
                Set.of(ComponentExecutionMode.EMBEDDED), ComponentStateModel.STATELESS,
                true, true, true, null, Set.of(), Map.of());
    }
}
