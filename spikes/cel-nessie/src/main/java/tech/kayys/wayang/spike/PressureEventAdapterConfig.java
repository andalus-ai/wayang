package tech.kayys.wayang.spike;

import java.util.Collections;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable, provider-specific configuration envelope.
 *
 * <p>Property values are opaque to the core SPI and must never be included in
 * diagnostics by the registry. Providers own validation and secret handling.</p>
 */
public record PressureEventAdapterConfig(
        String providerId,
        String providerVersion,
        Map<String, String> properties) {

    public PressureEventAdapterConfig {
        providerId = requireIdentity(providerId, "providerId");
        providerVersion = requireIdentity(providerVersion, "providerVersion");
        Objects.requireNonNull(properties, "properties");
        var copy = new TreeMap<String, String>();
        properties.forEach((key, value) -> {
            var safeKey = requireIdentity(key, "property key");
            copy.put(safeKey, Objects.requireNonNull(value, "property value"));
        });
        properties = Collections.unmodifiableMap(copy);
    }

    private static String requireIdentity(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be non-blank and trimmed");
        }
        return value;
    }
}
