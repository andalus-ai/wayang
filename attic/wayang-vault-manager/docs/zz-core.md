package tech.kayys.wayang.security.secrets;

/**
 * Request to store a secret
 */
record StoreSecretRequest(
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
    }

    public static Builder builder() {
        return new Builder();
    }

    public static class Builder {
        private String tenantId;
        private String path;
        private Map<String, String> data;
        private SecretType type = SecretType.GENERIC;
        private Duration ttl;
        private Map<String, String> metadata = Map.of();
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
            this.metadata = metadata;
            return this;
        }

        public Builder rotatable(boolean rotatable) {
            this.rotatable = rotatable;
            return this;
        }

        public StoreSecretRequest build() {
            return new StoreSecretRequest(tenantId, path, data, type, ttl, metadata, rotatable);
        }
    }
}

/**
 * Request to retrieve a secret
 */
record RetrieveSecretRequest(
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

    public static RetrieveSecretRequest of(String tenantId, String path) {
        return new RetrieveSecretRequest(tenantId, path, Optional.empty());
    }

    public static RetrieveSecretRequest of(String tenantId, String path, int version) {
        return new RetrieveSecretRequest(tenantId, path, Optional.of(version));
    }
}

/**
 * Request to delete a secret
 */
record DeleteSecretRequest(
    String tenantId,
    String path,
    boolean hardDelete,
    String reason
) {
    public static DeleteSecretRequest soft(String tenantId, String path, String reason) {
        return new DeleteSecretRequest(tenantId, path, false, reason);
    }

    public static DeleteSecretRequest hard(String tenantId, String path, String reason) {
        return new DeleteSecretRequest(tenantId, path, true, reason);
    }
}

/**
 * Request to rotate a secret
 */
record RotateSecretRequest(
    String tenantId,
    String path,
    Map<String, String> newData,
    boolean deprecateOld
) {
    public static RotateSecretRequest of(String tenantId, String path, Map<String, String> newData) {
        return new RotateSecretRequest(tenantId, path, newData, true);
    }
}

/**
 * Secret with decrypted data
 */
record Secret(
    String tenantId,
    String path,
    Map<String, String> data,
    SecretMetadata metadata
) {}

/**
 * Secret metadata (no sensitive data)
 */
record SecretMetadata(
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
) {}

/**
 * Secret type enumeration
 */
enum SecretType {
    GENERIC,
    API_KEY,
    DATABASE_CREDENTIAL,
    OAUTH_TOKEN,
    SSH_KEY,
    TLS_CERTIFICATE,
    ENCRYPTION_KEY,
    AWS_CREDENTIAL,
    AZURE_CREDENTIAL,
    GCP_CREDENTIAL
}

/**
 * Secret status
 */
enum SecretStatus {
    ACTIVE,
    DEPRECATED,
    DELETED,
    EXPIRED,
    ROTATED
}

/**
 * Health status for secret backend
 */
record HealthStatus(
    boolean healthy,
    String backend,
    Map<String, Object> details,
    Optional<String> error
) {
    public static HealthStatus healthy(String backend) {
        return new HealthStatus(true, backend, Map.of(), Optional.empty());
    }

    public static HealthStatus unhealthy(String backend, String error) {
        return new HealthStatus(false, backend, Map.of(), Optional.of(error));
    }
}

/**
 * Exception thrown when secret operations fail
 */
class SecretException extends RuntimeException {
    private final ErrorCode errorCode;

    public SecretException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SecretException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    public enum ErrorCode {
        SECRET_NOT_FOUND,
        PERMISSION_DENIED,
        BACKEND_UNAVAILABLE,
        ENCRYPTION_FAILED,
        DECRYPTION_FAILED,
        INVALID_PATH,
        QUOTA_EXCEEDED,
        VERSION_NOT_FOUND,
        ROTATION_FAILED
    }
}