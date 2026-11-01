package tech.kayys.wayang.security.secrets.dto;

import java.util.Map;

/**
 * Request to rotate a secret (create new version, optionally deprecate old).
 */
public record RotateSecretRequest(
    String tenantId,
    String path,
    Map<String, String> newData,
    boolean deprecateOld
) {
    public RotateSecretRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be empty");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }
        if (newData == null || newData.isEmpty()) {
            throw new IllegalArgumentException("newData cannot be empty");
        }
        newData = Map.copyOf(newData);
    }

    /**
     * Create a rotation request that deprecates the old secret
     */
    public static RotateSecretRequest deprecateOld(String tenantId, String path, Map<String, String> newData) {
        return new RotateSecretRequest(tenantId, path, newData, true);
    }

    /**
     * Create a rotation request without deprecating the old secret
     */
    public static RotateSecretRequest keepOld(String tenantId, String path, Map<String, String> newData) {
        return new RotateSecretRequest(tenantId, path, newData, false);
    }
}
