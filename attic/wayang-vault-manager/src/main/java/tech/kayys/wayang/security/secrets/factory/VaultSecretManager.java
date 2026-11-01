package tech.kayys.wayang.security.secrets.factory;

import tech.kayys.wayang.security.secrets.core.SecretManager;

/**
 * HashiCorp Vault implementation of SecretManager.
 * 
 * Features:
 * - Multi-engine support (KV v1, KV v2, etc.)
 * - Dynamic secret generation
 * - Automatic renewal
 * - Audit logging
 * - High availability
 */
public interface VaultSecretManager extends SecretManager {
    // Specific Vault implementations
}
