package tech.kayys.wayang.security.secrets.dto;

import java.time.Duration;
import java.util.HashMap;
import java.util.Map;

/**
 * Request to store a secret in the secret manager.
 * Validates all required fields during construction.
 */
public record StoreSecretRequest(
    String tenantId,
    String path,
    Map<String, String> data,
    SecretType type,
    Duration ttl,
    Map<String, String> metadata,
    boolean rotatable
) {
    public StoreSecretRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be empty");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }
        if (data == null || data.isEmpty()) {
            throw new IllegalArgumentException("data cannot be empty");
        }
        if (type == null) {
            throw new IllegalArgumentException("type cannot be null");
        }
        // Make defensive copies to prevent external modification
        data = Map.copyOf(data);
        metadata = metadata != null ? Map.copyOf(metadata) : Map.of();
    }

    public static Builder builder() {
        return new Builder();
    }

    /**
     * Builder for fluent construction of StoreSecretRequest
     */
    public static class Builder {
        private String tenantId;
        private String path;
        private Map<String, String> data;
        private SecretType type = SecretType.GENERIC;
        private Duration ttl;
        private Map<String, String> metadata = new HashMap<>();
        private boolean rotatable = false;

        public Builder tenantId(String tenantId) {
            this.tenantId = tenantId;
            return this;
        }

        public Builder path(String path) {
            this.path = path;
            return this;
        }

        public Builder data(Map<String, String> data) {
            this.data = data;
            return this;
        }

        public Builder type(SecretType type) {
            this.type = type;
            return this;
        }

        public Builder ttl(Duration ttl) {
            this.ttl = ttl;
            return this;
        }

        public Builder metadata(Map<String, String> metadata) {
            this.metadata = new HashMap<>(metadata);
            return this;
        }

        public Builder rotatable(boolean rotatable) {
            this.rotatable = rotatable;
            return this;
        }

        public StoreSecretRequest build() {
            return new StoreSecretRequest(
                tenantId, 
                path, 
                data != null ? new HashMap<>(data) : Map.of(), 
                type, 
                ttl, 
                metadata, 
                rotatable
            );
        }
    }
}
