package tech.kayys.wayang.runtime;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates the relationship between a plugin manifest and its contributed component descriptors. */
public final class PluginManifestValidator {

    private PluginManifestValidator() {
    }

    /**
     * Ensures that a plugin's declared component inventory exactly matches its
     * supplied descriptors. This is a consistency check, not signature verification.
     */
    public static List<ComponentDescriptor> validateComponents(
            PluginManifest manifest,
            Collection<ComponentDescriptor> descriptors) {
        Objects.requireNonNull(manifest, "manifest");
        Objects.requireNonNull(descriptors, "descriptors");

        Map<String, String> declared = new LinkedHashMap<>();
        manifest.components().forEach(component -> declared.put(component.id(), component.version()));

        Map<String, ComponentDescriptor> supplied = new LinkedHashMap<>();
        for (ComponentDescriptor descriptor : descriptors) {
            Objects.requireNonNull(descriptor, "descriptor");
            if (supplied.putIfAbsent(descriptor.id(), descriptor) != null) {
                throw new IllegalArgumentException("Duplicate component descriptor '" + descriptor.id() + "'");
            }
        }

        if (!declared.keySet().equals(supplied.keySet())) {
            throw new IllegalArgumentException("Plugin component inventory mismatch: declared="
                    + declared.keySet() + ", supplied=" + supplied.keySet());
        }
        declared.forEach((id, version) -> {
            if (!version.equals(supplied.get(id).version())) {
                throw new IllegalArgumentException("Plugin component version mismatch for '" + id
                        + "': manifest=" + version + ", descriptor=" + supplied.get(id).version());
            }
        });
        return List.copyOf(supplied.values());
    }
}
