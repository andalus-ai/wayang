package tech.kayys.wayang.security.secrets.factory;

import tech.kayys.wayang.security.secrets.core.SecretManager;

/**
 * Local encrypted storage implementation of SecretManager.
 * 
 * Features:
 * - File-based storage with AES-256 encryption
 * - Suitable for development and standalone deployment
 * - No external dependencies
 * - Fast startup
 * 
 * NOT RECOMMENDED for production environments.
 */
public interface LocalEncryptedSecretManager extends SecretManager {
    // Specific local implementation
}
