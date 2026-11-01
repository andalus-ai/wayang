package tech.kayys.wayang.security.secrets.factory;

import io.quarkus.arc.lookup.LookupIfProperty;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Produces;
import jakarta.inject.Inject;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.core.SecretManager;
import tech.kayys.wayang.security.secrets.aws.AWSSecretsManager;
import tech.kayys.wayang.security.secrets.vault.VaultSecretManager;
import tech.kayys.wayang.security.secrets.local.LocalEncryptedSecretManagerImpl;
import tech.kayys.wayang.security.secrets.core.DefaultSecretManager;

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
 * secret.backend=vault|aws|azure|local (default: local)
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
    LocalEncryptedSecretManagerImpl localSecretManager;

    /**
     * Produces the appropriate SecretManager bean based on configuration
     */
    @Produces
    @ApplicationScoped
    @DefaultSecretManager
    public SecretManager createSecretManager() {
        LOG.infof("Initializing secret manager with backend: %s", backend);

        SecretManager manager = switch (backend.toLowerCase().trim()) {
            case "vault" -> {
                LOG.info("Using HashiCorp Vault secret manager");
                if (vaultSecretManager == null) {
                    throw new IllegalStateException("Vault secret manager not available. Check configuration.");
                }
                yield vaultSecretManager;
            }
            case "aws" -> {
                LOG.info("Using AWS Secrets Manager");
                if (awsSecretsManager == null) {
                    throw new IllegalStateException("AWS Secrets Manager not available. Check configuration.");
                }
                yield awsSecretsManager;
            }
            case "local" -> {
                LOG.info("Using local encrypted secret manager");
                if (localSecretManager == null) {
                    throw new IllegalStateException("Local secret manager not available. Check configuration.");
                }
                yield localSecretManager;
            }
            default -> {
                LOG.warnf("Unknown backend '%s', falling back to local", backend);
                if (localSecretManager == null) {
                    throw new IllegalStateException("Local secret manager not available as fallback.");
                }
                yield localSecretManager;
            }
        };

        return manager;
    }
}
