package tech.kayys.wayang.security.secrets;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.aws.AWSSecretsManager;
import tech.kayys.wayang.security.secrets.local.LocalEncryptedSecretManager;
import tech.kayys.wayang.security.secrets.vault.VaultSecretManager;

/**
 * Factory for creating the appropriate SecretManager based on configuration.
 * 
 * Supported backends:
 * - vault: HashiCorp Vault (production recommended)
 * - aws: AWS Secrets Manager
 * - azure: Azure Key Vault
 * - local: Local encrypted storage (dev/standalone)
 * 
 * Configuration:
 * secret.backend=vault|aws|azure|local
 */
@ApplicationScoped
public class SecretManagerFactory {

    private static final Logger LOG = Logger.getLogger(SecretManagerFactory.class);

    @ConfigProperty(name = "secret.backend", defaultValue = "local")
    String backend;

    @Inject
    @LookupIfProperty(name = "secret.backend", stringValue = "vault")
    VaultSecretManager vaultSecretManager;

    @Inject
    @LookupIfProperty(name = "secret.backend", stringValue = "aws")
    AWSSecretsManager awsSecretsManager;

    @Inject
    @LookupIfProperty(name = "secret.backend", stringValue = "local")
    LocalEncryptedSecretManager localSecretManager;

    @Produces
    @ApplicationScoped
    public SecretManager createSecretManager() {
        LOG.infof("Initializing secret manager with backend: %s", backend);

        return switch (backend.toLowerCase()) {
            case "vault" -> {
                LOG.info("Using HashiCorp Vault secret manager");
                yield vaultSecretManager;
            }
            case "aws" -> {
                LOG.info("Using AWS Secrets Manager");
                yield awsSecretsManager;
            }
            case "local" -> {
                LOG.info("Using local encrypted secret manager");
                yield localSecretManager;
            }
            default -> {
                LOG.warnf("Unknown backend '%s', falling back to local", backend);
                yield localSecretManager;
            }
        };
    }
}

/**
 * Audit logger for secret operations
 */
@ApplicationScoped
class VaultAuditLogger {
    
    private static final Logger LOG = Logger.getLogger(VaultAuditLogger.class);

    // In production, this should integrate with the AuditPayload system
    public void logSecretStore(String tenantId, String path, int version) {
        LOG.infof("AUDIT: Secret stored - tenant=%s, path=%s, version=%d", 
            tenantId, path, version);
    }

    public void logSecretRetrieve(String tenantId, String path, int version) {
        LOG.debugf("AUDIT: Secret retrieved - tenant=%s, path=%s, version=%d", 
            tenantId, path, version);
    }

    public void logSecretDelete(String tenantId, String path, boolean hard, String reason) {
        LOG.infof("AUDIT: Secret deleted - tenant=%s, path=%s, hard=%b, reason=%s", 
            tenantId, path, hard, reason);
    }

    public void logSecretRotate(String tenantId, String path, int oldVersion, int newVersion) {
        LOG.infof("AUDIT: Secret rotated - tenant=%s, path=%s, from=%d, to=%d", 
            tenantId, path, oldVersion, newVersion);
    }
}

/**
 * Vault token manager for automatic renewal
 */
@ApplicationScoped
class VaultTokenManager {
    
    private static final Logger LOG = Logger.getLogger(VaultTokenManager.class);

    // Placeholder for token renewal logic
    // In production, implement automatic token renewal with AppRole
}

