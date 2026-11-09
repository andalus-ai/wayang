package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class ComponentExecutionCapabilitiesTest {

    private static ComponentDescriptor component(String id, String version) {
        return new ComponentDescriptor(id, version, ComponentDescriptor.Kind.NODE, "wayang-eip",
                id, Set.of(), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of());
    }

    @Test
    void acceptsEmbeddedAndRemoteCapabilitiesWhenPayloadsArePortable() {
        var capabilities = new ComponentExecutionCapabilities(
                "wayang.extension.document-extractor", "1.2.0",
                Set.of(ComponentExecutionMode.EMBEDDED, ComponentExecutionMode.REMOTE),
                ComponentStateModel.STATELESS, true, true, false, null,
                Set.of("document.extract"), Map.of("memory", "512Mi"));

        assertTrue(capabilities.supportsRemoteExecution());
        assertFalse(capabilities.requiresAffinity());
        assertEquals("512Mi", capabilities.resourceHints().get("memory"));
        assertThrows(UnsupportedOperationException.class,
                () -> capabilities.resourceHints().put("cpu", "1"));
    }

    @Test
    void remoteModesRequirePortablePayloads() {
        assertThrows(IllegalArgumentException.class, () -> new ComponentExecutionCapabilities(
                "wayang.extension.remote", "1.0.0", Set.of(ComponentExecutionMode.REMOTE),
                ComponentStateModel.STATELESS, false, true, true, null, Set.of(), Map.of()));
    }

    @Test
    void affineComponentsRequireAnAffinityKey() {
        assertThrows(IllegalArgumentException.class, () -> new ComponentExecutionCapabilities(
                "wayang.extension.stateful", "1.0.0", Set.of(ComponentExecutionMode.EMBEDDED),
                ComponentStateModel.AFFINE, false, true, false, null, Set.of(), Map.of()));

        var affine = new ComponentExecutionCapabilities(
                "wayang.extension.stateful", "1.0.0", Set.of(ComponentExecutionMode.REMOTE),
                ComponentStateModel.AFFINE, true, true, false, "sessionId", Set.of(), Map.of());
        assertTrue(affine.requiresAffinity());
    }

    @Test
    void registryRequiresAnAvailableExactComponentVersion() {
        var registry = new ComponentRegistry(
                List.of(component("wayang.extension.worker", "1.0.0")), Profile.FULL);
        var declaration = new ComponentExecutionCapabilities(
                "wayang.extension.worker", "1.0.0",
                Set.of(ComponentExecutionMode.EMBEDDED, ComponentExecutionMode.ISOLATED_LOCAL),
                ComponentStateModel.STATELESS, false, true, true, null, Set.of(), Map.of());

        var capabilities = ComponentExecutionCapabilityRegistry.of(registry, List.of(declaration));
        assertTrue(capabilities.supports("wayang.extension.worker", ComponentExecutionMode.EMBEDDED));
        assertFalse(capabilities.supports("wayang.extension.worker", ComponentExecutionMode.REMOTE));
        assertFalse(capabilities.supports("wayang.extension.unknown", ComponentExecutionMode.EMBEDDED));
    }

    @Test
    void registryRejectsUnknownDuplicateAndVersionMismatchedDeclarations() {
        var registry = new ComponentRegistry(
                List.of(component("wayang.extension.worker", "1.0.0")), Profile.FULL);
        var correct = new ComponentExecutionCapabilities(
                "wayang.extension.worker", "1.0.0", Set.of(ComponentExecutionMode.EMBEDDED),
                ComponentStateModel.STATELESS, false, false, false, null, Set.of(), Map.of());
        var wrongVersion = new ComponentExecutionCapabilities(
                "wayang.extension.worker", "2.0.0", Set.of(ComponentExecutionMode.EMBEDDED),
                ComponentStateModel.STATELESS, false, false, false, null, Set.of(), Map.of());

        assertThrows(IllegalArgumentException.class,
                () -> ComponentExecutionCapabilityRegistry.of(registry, List.of(wrongVersion)));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentExecutionCapabilityRegistry.of(registry, List.of(correct, correct)));
        assertThrows(IllegalArgumentException.class,
                () -> ComponentExecutionCapabilityRegistry.of(registry, List.of(
                        new ComponentExecutionCapabilities("wayang.extension.missing", "1.0.0",
                                Set.of(ComponentExecutionMode.EMBEDDED), ComponentStateModel.STATELESS,
                                false, false, false, null, Set.of(), Map.of()))));
    }

    @Test
    void missingMetadataDoesNotImplyRemoteSupport() {
        var registry = new ComponentRegistry(
                List.of(component("wayang.extension.legacy", "1.0.0")), Profile.FULL);
        var capabilities = ComponentExecutionCapabilityRegistry.empty();

        assertNull(capabilities.find("wayang.extension.legacy"));
        assertFalse(capabilities.supports("wayang.extension.legacy", ComponentExecutionMode.REMOTE));
    }
}
