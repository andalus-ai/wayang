package tech.kayys.wayang.spike;

import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.ServiceLoader;

/**
 * Deterministic registry for optional pressure-event adapter providers.
 *
 * <p>Selection always requires the exact provider ID and version. Duplicate
 * registrations fail closed; classpath order never chooses a winner.</p>
 */
public final class PressureEventAdapterRegistry {
    private final Map<ProviderKey, PressureEventAdapterProvider> providers;

    private PressureEventAdapterRegistry(Collection<? extends PressureEventAdapterProvider> candidates) {
        Objects.requireNonNull(candidates, "candidates");
        var indexed = new LinkedHashMap<ProviderKey, PressureEventAdapterProvider>();
        for (var provider : candidates) {
            Objects.requireNonNull(provider, "provider");
            var key = new ProviderKey(provider.id(), provider.version());
            if (indexed.putIfAbsent(key, provider) != null) {
                throw new IllegalArgumentException(
                        "Duplicate pressure-event adapter provider: " + key.id() + "@" + key.version());
            }
        }
        this.providers = Map.copyOf(indexed);
    }

    /** Build an explicit registry; useful for hosts and deterministic tests. */
    public static PressureEventAdapterRegistry of(
            Collection<? extends PressureEventAdapterProvider> providers) {
        return new PressureEventAdapterRegistry(providers);
    }

    /** Discover providers through Java ServiceLoader using the supplied classloader. */
    public static PressureEventAdapterRegistry load(ClassLoader classLoader) {
        Objects.requireNonNull(classLoader, "classLoader");
        return of(ServiceLoader.load(PressureEventAdapterProvider.class, classLoader).stream()
                .map(ServiceLoader.Provider::get).toList());
    }

    public PressureEventAdapterProvider require(String providerId, String providerVersion) {
        var key = new ProviderKey(providerId, providerVersion);
        var provider = providers.get(key);
        if (provider == null) {
            throw new IllegalArgumentException(
                    "No pressure-event adapter provider registered for " + key.id() + "@" + key.version());
        }
        return provider;
    }

    /** Create and identity-check an adapter; caller owns its lifecycle. */
    public PressureEventAdapter create(PressureEventAdapterConfig config) {
        Objects.requireNonNull(config, "config");
        var provider = require(config.providerId(), config.providerVersion());
        var adapter = Objects.requireNonNull(provider.create(config), "provider returned null adapter");
        if (!config.providerId().equals(adapter.providerId())
                || !config.providerVersion().equals(adapter.providerVersion())) {
            try {
                adapter.close();
            } catch (RuntimeException ignored) {
                // Preserve the identity mismatch as the primary failure.
            }
            throw new IllegalStateException("Created adapter identity does not match selected provider");
        }
        return adapter;
    }

    public int size() {
        return providers.size();
    }

    private record ProviderKey(String id, String version) {
        private ProviderKey {
            id = requireIdentity(id, "provider id");
            version = requireIdentity(version, "provider version");
        }

        private static String requireIdentity(String value, String name) {
            Objects.requireNonNull(value, name);
            if (value.isBlank() || !value.equals(value.trim())) {
                throw new IllegalArgumentException(name + " must be non-blank and trimmed");
            }
            return value;
        }
    }
}
