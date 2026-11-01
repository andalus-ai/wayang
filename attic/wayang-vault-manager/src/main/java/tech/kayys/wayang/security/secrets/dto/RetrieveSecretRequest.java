package tech.kayys.wayang.security.secrets.dto;

import java.util.Optional;

/**
 * Request to retrieve a secret by path and optional version.
 */
public record RetrieveSecretRequest(
    String tenantId,
    String path,
    Optional<Integer> version
) {
    public RetrieveSecretRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be empty");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }
        if (version == null) {
            version = Optional.empty();
        }
    }

    /**
     * Create a request for the latest version of a secret
     */
    public static RetrieveSecretRequest latest(String tenantId, String path) {
        return new RetrieveSecretRequest(tenantId, path, Optional.empty());
    }

    /**
     * Create a request for a specific version of a secret
     */
    public static RetrieveSecretRequest version(String tenantId, String path, int version) {
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
        return new RetrieveSecretRequest(tenantId, path, Optional.of(version));
    }
}
