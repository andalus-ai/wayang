package tech.kayys.wayang.security.secrets.dto;

import java.util.Map;

/**
 * Secret with decrypted data and metadata.
 * This record is only returned after authentication and authorization checks.
 */
public record Secret(
    String tenantId,
    String path,
    Map<String, String> data,
    SecretMetadata metadata
) {
    public Secret {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be empty");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("data cannot be empty");
        }
        if (metadata == null) {
            throw new IllegalArgumentException("metadata cannot be null");
        }
        // Defensive copy
        data = Map.copyOf(data);
    }
}
