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

class WayangCoreMetadataMapperTest {
    private final WayangCoreMetadataMapper mapper = new WayangCoreMetadataMapper();

    @Test
    void projectsManifestAndPreservesWayangSpecificFields() {
        var manifest = manifest();
        var descriptor = descriptor();
        var execution = execution(ComponentStateModel.AFFINE, "session", Set.of("gpu", "media.decode"),
                Map.of("memory", "2Gi", "cpu", "2"));
        var result = mapper.map(manifest, List.of(descriptor), List.of(execution));
        var component = result.descriptor().components().get(0);

        assertEquals("acme.media", result.descriptor().id().toString());
        assertEquals("http", component.metadata().get("wayang:executor-type"));
        assertEquals("[\"MEDIA\"]", component.metadata().get("wayang:required-features"));
        assertEquals("{\"cpu\":\"2\",\"memory\":\"2Gi\"}", component.metadata().get("wayang:resource-hints"));
        assertEquals("session", component.metadata().get("wayang:affinity-key"));
        assertEquals(1, result.descriptor().dependencies().size());
        assertTrue(result.hasWarnings());
        assertTrue(result.diagnostics().stream().anyMatch(d -> d.fieldPath().endsWith("configSchema")));
    }

    @Test
    void canonicalJsonSortsMapKeysAndEscapesStrings() {
        assertEquals("{\"a\":[1,true],\"z\":\"line\\nquote\\\"\\\\\"}",
                WayangCoreMetadataMapper.json(Map.of("z", "line\nquote\"\\", "a", List.of(1, true))));
    }

    @Test
    void rejectsMissingExecutionDeclarationInsteadOfInferringFromIsolation() {
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(manifest(), List.of(descriptor()), List.of()));
    }

    @Test
    void rejectsUnexpectedDescriptorsAndExecutionDeclarations() {
        var extra = new ComponentDescriptor("acme.media.extra", "1.2.3", ComponentDescriptor.Kind.NODE,
                "http", "", Set.of(), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of());
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(manifest(), List.of(descriptor(), extra), List.of(execution())));
    }

    @Test
    void rejectsMalformedPluginApiVersion() {
        var malformed = new PluginManifest("acme.media", "1.2.3", "1.x", "Acme", ">=1.0 <2.0",
                List.of(new PluginManifest.ProvidedComponent("acme.media.fetch", "1.2.3")),
                List.of(), Set.of(), null);
        assertThrows(IllegalArgumentException.class,
                () -> mapper.map(malformed, List.of(descriptor()), List.of(execution())));
    }

    private static PluginManifest manifest() {
        return new PluginManifest("acme.media", "1.2.3", "1.0", "Acme", ">=1.0 <2.0",
                List.of(new PluginManifest.ProvidedComponent("acme.media.fetch", "1.2.3")),
                List.of(new PluginManifest.Dependency("acme.common", "^2.0.0", true)),
                Set.of("media:read"), "Apache-2.0");
    }

    private static ComponentDescriptor descriptor() {
        return new ComponentDescriptor("acme.media.fetch", "1.2.3", ComponentDescriptor.Kind.NODE,
                "http", "Fetch", Set.of(tech.kayys.wayang.runtime.Profile.Feature.MEDIA), List.of(),
                ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of("media.fetch"),
                "Fetch", "media", "download",
                Map.of("type", "object", "properties", Map.of("url", Map.of("type", "string"))),
                Map.of("widget", "url"));
    }

    private static ComponentExecutionCapabilities execution() {
        return execution(ComponentStateModel.STATELESS, null, Set.of(), Map.of());
    }

    private static ComponentExecutionCapabilities execution(ComponentStateModel state, String affinity,
            Set<String> required, Map<String, String> hints) {
        return new ComponentExecutionCapabilities("acme.media.fetch", "1.2.3",
                Set.of(ComponentExecutionMode.EMBEDDED), state, true, true, true, affinity, required, hints);
    }
}
