package tech.kayys.wayang.security.secrets.factory;

import tech.kayys.wayang.security.secrets.core.SecretManager;

/**
 * AWS Secrets Manager implementation of SecretManager.
 * 
 * Features:
 * - Automatic rotation via Lambda
 * - Replication across regions
 * - Multi-account access
 * - CloudTrail integration
 * - Encryption via KMS
 */
public interface AWSSecretsManager extends SecretManager {
    // Specific AWS implementations
}
