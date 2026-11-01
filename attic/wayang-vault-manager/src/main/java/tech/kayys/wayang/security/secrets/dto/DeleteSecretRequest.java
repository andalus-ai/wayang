package tech.kayys.wayang.security.secrets.dto;

/**
 * Request to delete a secret with soft or hard delete options.
 * Soft delete: Secret is retained for retention period (default)
 * Hard delete: Secret is immediately and permanently deleted
 */
public record DeleteSecretRequest(
    String tenantId,
    String path,
    boolean hardDelete,
    String reason
) {
    public DeleteSecretRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId cannot be empty");
        }
        if (path == null || path.isBlank()) {
            throw new IllegalArgumentException("path cannot be empty");
        }
        if (reason == null || reason.isBlank()) {
            throw new IllegalArgumentException("reason cannot be empty");
        }
    }

    /**
     * Create a soft delete request (allows recovery)
     */
    public static DeleteSecretRequest soft(String tenantId, String path, String reason) {
        return new DeleteSecretRequest(tenantId, path, false, reason);
    }

    /**
     * Create a hard delete request (permanent)
     */
    public static DeleteSecretRequest hard(String tenantId, String path, String reason) {
        return new DeleteSecretRequest(tenantId, path, true, reason);
    }
}
