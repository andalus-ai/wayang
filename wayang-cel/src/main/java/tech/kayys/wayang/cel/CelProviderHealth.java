package tech.kayys.wayang.cel;

import java.util.Objects;

/**
 * Safe, non-secret provider identity for diagnostics and operational health.
 * It intentionally contains no implementation class names or configuration.
 */
public record CelProviderHealth(String providerId, String providerVersion, boolean available,
        String statusCode) {
    public CelProviderHealth {
        if (providerId == null || providerId.isBlank()) {
            throw new IllegalArgumentException("providerId must not be blank");
        }
        if (providerVersion == null || providerVersion.isBlank()) {
            throw new IllegalArgumentException("providerVersion must not be blank");
        }
        if (statusCode == null || statusCode.isBlank()) {
            throw new IllegalArgumentException("statusCode must not be blank");
        }
        if (available && !"READY".equals(statusCode)) {
            throw new IllegalArgumentException("Available providers must report READY");
        }
    }

    public static CelProviderHealth ready(CelProviderRegistry.ProviderKey key) {
        Objects.requireNonNull(key, "key");
        return new CelProviderHealth(key.providerId(), key.providerVersion(), true, "READY");
    }

    public static CelProviderHealth unavailable(CelProviderRegistry.ProviderKey key, String statusCode) {
        Objects.requireNonNull(key, "key");
        if ("READY".equals(statusCode)) {
            throw new IllegalArgumentException("Unavailable provider cannot report READY");
        }
        return new CelProviderHealth(key.providerId(), key.providerVersion(), false, statusCode);
    }
}
