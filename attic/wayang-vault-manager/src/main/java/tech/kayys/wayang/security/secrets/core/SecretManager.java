package tech.kayys.wayang.security.secrets.core;

import io.smallrye.mutiny.Uni;
import java.util.List;
import tech.kayys.wayang.security.secrets.dto.*;

/**
 * Core abstraction for secret management across multiple backends.
 * 
 * Supported backends:
 * - HashiCorp Vault (recommended for production)
 * - AWS Secrets Manager
 * - Azure Key Vault
 * - Local encrypted storage (dev/standalone)
 * 
 * Features:
 * - Automatic secret rotation
 * - Version management
 * - Audit logging
 * - Multi-tenancy support
 * - Encryption at rest
 * - TTL and lease management
 * 
 * All operations are asynchronous and return Uni<T> for Quarkus integration.
 */
public interface SecretManager {

    /**
     * Store a secret with automatic encryption.
     * 
     * @param request Secret storage request containing tenant ID, path, data, type, and metadata
     * @return Secret metadata with assigned version number
     * @throws IllegalArgumentException if request validation fails
     * @throws tech.kayys.wayang.security.secrets.exception.SecretException on backend errors
     */
    Uni<SecretMetadata> store(StoreSecretRequest request);

    /**
     * Retrieve a secret by path and optional version.
     * 
     * Returns decrypted secret data only to authenticated and authorized users.
     * 
     * @param request Secret retrieval request with version (optional)
     * @return Decrypted secret value with metadata
     * @throws tech.kayys.wayang.security.secrets.exception.SecretException if not found or access denied
     */
    Uni<Secret> retrieve(RetrieveSecretRequest request);

    /**
     * Delete a secret (soft delete with retention by default).
     * 
     * Soft delete retains the secret for the configured retention period.
     * Hard delete immediately and permanently removes the secret.
     * 
     * @param request Delete request specifying soft/hard delete and reason
     * @return Void on successful deletion
     * @throws tech.kayys.wayang.security.secrets.exception.SecretException on failure
     */
    Uni<Void> delete(DeleteSecretRequest request);

    /**
     * List secrets in a path (metadata only, no values).
     * 
     * Useful for discovering secrets and managing lifecycle without exposing values.
     * 
     * @param tenantId Tenant identifier
     * @param path     Path prefix for filtering
     * @return List of secret metadata (without secret values)
     * @throws tech.kayys.wayang.security.secrets.exception.SecretException on access denied
     */
    Uni<List<SecretMetadata>> list(String tenantId, String path);

    /**
     * Rotate a secret (create new version, optionally deprecate old).
     * 
     * Rotation creates a new version while optionally marking the old as deprecated.
     * Clients should migrate to the new version within the grace period.
     * 
     * @param request Rotation request with new data and deprecation flag
     * @return New secret metadata with incremented version
     * @throws tech.kayys.wayang.security.secrets.exception.SecretException on failure
     */
    Uni<SecretMetadata> rotate(RotateSecretRequest request);

    /**
     * Check if a secret exists without retrieving it.
     * 
     * Fast operation for conditional logic without decryption.
     * 
     * @param tenantId Tenant identifier
     * @param path     Secret path
     * @return True if secret exists and is not deleted
     * @throws tech.kayys.wayang.security.secrets.exception.SecretException on access denied
     */
    Uni<Boolean> exists(String tenantId, String path);

    /**
     * Get secret metadata without retrieving the value.
     * 
     * Returns metadata only (creation date, version, status, etc.)
     * Safe to expose in logs without revealing sensitive information.
     * 
     * @param tenantId Tenant identifier
     * @param path     Secret path
     * @return Secret metadata
     * @throws tech.kayys.wayang.security.secrets.exception.SecretException if not found
     */
    Uni<SecretMetadata> getMetadata(String tenantId, String path);

    /**
     * Health check for the secret backend.
     * 
     * Returns backend connectivity and readiness status.
     * 
     * @return Health status with backend details
     */
    Uni<HealthStatus> health();
}
