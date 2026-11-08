package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

class PluginManifestTest {

    private static PluginManifest manifest() {
        return new PluginManifest(
                "wayang.plugin.documents", "1.2.0", "1.0", "acme",
                ">=1.0.0 <2.0.0",
                List.of(new PluginManifest.ProvidedComponent("wayang.document.extract", "1.2.0")),
                List.of(new PluginManifest.Dependency("wayang.plugin.core", "^1.0.0", false)),
                Set.of("network:document-store", "credentials:document-store"), "Apache-2.0");
    }

    private static ComponentDescriptor descriptor(String version) {
        return new ComponentDescriptor("wayang.document.extract", version,
                ComponentDescriptor.Kind.NODE, "wayang-eip", "Extract document",
                Set.of(), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of());
    }

    @Test
    void createsImmutableManifestWithVersionedComponentsAndDependencies() {
        PluginManifest manifest = manifest();

        assertEquals("wayang.plugin.documents", manifest.id());
        assertEquals("1.2.0", manifest.version());
        assertEquals("^1.0.0", manifest.dependencies().getFirst().versionRange());
        assertThrows(UnsupportedOperationException.class,
                () -> manifest.permissions().add("filesystem:/tmp"));
    }

    @Test
    void rejectsInvalidVersionsAndDuplicateInventoryEntries() {
        assertThrows(IllegalArgumentException.class, () -> new PluginManifest(
                "wayang.plugin.invalid", "latest", "1.0", "acme", "*",
                List.of(new PluginManifest.ProvidedComponent("wayang.component.one", "1.0.0")),
                List.of(), Set.of(), null));

        assertThrows(IllegalArgumentException.class, () -> new PluginManifest(
                "wayang.plugin.invalid", "1.0.0", "1.0", "acme", "*",
                List.of(new PluginManifest.ProvidedComponent("wayang.component.one", "1.0.0"),
                        new PluginManifest.ProvidedComponent("wayang.component.one", "1.0.1")),
                List.of(), Set.of(), null));
    }

    @Test
    void rejectsSelfAndDuplicateDependenciesAndMalformedPermissions() {
        assertThrows(IllegalArgumentException.class, () -> new PluginManifest(
                "wayang.plugin.sample", "1.0.0", "1.0", "acme", "*",
                List.of(new PluginManifest.ProvidedComponent("wayang.component.one", "1.0.0")),
                List.of(new PluginManifest.Dependency("wayang.plugin.sample", "*", false)), Set.of(), null));

        assertThrows(IllegalArgumentException.class, () -> new PluginManifest(
                "wayang.plugin.sample", "1.0.0", "1.0", "acme", "*",
                List.of(new PluginManifest.ProvidedComponent("wayang.component.one", "1.0.0")),
                List.of(), Set.of("all-the-things"), null));
    }

    @Test
    void validatesManifestInventoryAgainstSuppliedDescriptors() {
        PluginManifest manifest = manifest();

        assertEquals(List.of("wayang.document.extract"),
                PluginManifestValidator.validateComponents(manifest, List.of(descriptor("1.2.0")))
                        .stream().map(ComponentDescriptor::id).toList());

        assertThrows(IllegalArgumentException.class,
                () -> PluginManifestValidator.validateComponents(manifest, List.of(descriptor("2.0.0"))));
        assertThrows(IllegalArgumentException.class,
                () -> PluginManifestValidator.validateComponents(manifest, List.of()));
    }
}
