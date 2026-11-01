package tech.kayys.wayang.security.secrets.dto;

import java.time.Instant;
import java.util.Map;
import java.util.Optional;

/**
 * Secret metadata without sensitive data.
 * Safe to expose in logs and responses without exposing secret values.
 */
public record SecretMetadata(
    String tenantId,
    String path,
    int version,
    SecretType type,
    Instant createdAt,
    Instant updatedAt,
    Optional<Instant> expiresAt,
    String createdBy,
    Map<String, String> metadata,
    boolean rotatable,
    SecretStatus status
) {
    public SecretMetadata {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be empty");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }
        if (version < 0) {
            throw new IllegalArgumentException("version must be non-negative");
        }
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        if (createdAt == null) {
            throw new IllegalArgumentException("createdAt cannot be null");
        }
        if (updatedAt == null) {
            throw new IllegalArgumentException("updatedAt cannot be null");
        }
        if (createdBy == null || createdBy.isBlank()) {
            throw new IllegalArgumentException("createdBy cannot be empty");
        }
        if (status == null) {
            throw new IllegalArgumentException("status cannot be null");
        }
        if (expiresAt == null) {
            expiresAt = Optional.empty();
        }
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    /**
     * Check if the secret has expired
     */
    public boolean isExpired() {
        return expiresAt.isPresent() && expiresAt.get().isBefore(Instant.now());
    }
}
