package tech.kayys.wayang.security.secrets.deploy;

/**
 * Deployment configuration constants and enums for secret management system.
 * 
 * Contains:
 * - Environment configurations
 * - Backend types
 * - Deployment profiles
 * - Configuration properties
 */

/**
 * Supported secret backend implementations
 */
enum SecretBackend {
    /**
     * Local encrypted storage (development only)
     */
    LOCAL("local", "tech.kayys.wayang.security.secrets.local.LocalSecretManager"),
    
    /**
     * HashiCorp Vault
     */
    VAULT("vault", "tech.kayys.wayang.security.secrets.vault.VaultSecretManager"),
    
    /**
     * AWS Secrets Manager
     */
    AWS("aws", "tech.kayys.wayang.security.secrets.aws.AWSSecretsManager"),
    
    /**
     * Azure Key Vault
     */
    AZURE("azure", "tech.kayys.wayang.security.secrets.azure.AzureKeyVault");
    
    private final String id;
    private final String className;
    
    SecretBackend(String id, String className) {
        this.id = id;
        this.className = className;
    }
    
    public String getId() {
        return id;
    }
    
    public String getClassName() {
        return className;
    }
    
    public static SecretBackend fromId(String id) {
        for (SecretBackend backend : values()) {
            if (backend.id.equals(id)) {
                return backend;
            }
        }
        throw new IllegalArgumentException("Unknown secret backend: " + id);
    }
}

/**
 * Deployment environment
 */
enum Environment {
    /**
     * Local development
     */
    DEVELOPMENT("dev"),
    
    /**
     * Staging/testing
     */
    STAGING("staging"),
    
    /**
     * Production
     */
    PRODUCTION("prod");
    
    private final String name;
    
    Environment(String name) {
        this.name = name;
    }
    
    public String getName() {
        return name;
    }
    
    public static Environment fromName(String name) {
        for (Environment env : values()) {
            if (env.name.equals(name)) {
                return env;
            }
        }
        throw new IllegalArgumentException("Unknown environment: " + name);
    }
}

/**
 * Configuration properties for deployment
 */
public class DeploymentConfig {
    
    // Secret backend configuration
    public static final String SECRET_BACKEND = "secret.backend";
    public static final String MASTER_KEY = "secret.master-key";
    public static final String RETENTION_DAYS = "secret.retention-days";
    
    // Vault configuration
    public static final String VAULT_URL = "quarkus.vault.url";
    public static final String VAULT_TOKEN = "quarkus.vault.authentication.client-token";
    public static final String VAULT_KV_VERSION = "quarkus.vault.kv-secret-engine-version";
    public static final String VAULT_MOUNT_PATH = "quarkus.vault.kv-secret-engine-mount-path";
    
    // AWS configuration
    public static final String AWS_REGION = "quarkus.secretsmanager.region";
    public static final String AWS_ENDPOINT = "quarkus.secretsmanager.endpoint-override";
    public static final String AWS_KMS_KEY = "aws.secrets.kms-key-id";
    public static final String AWS_PREFIX = "aws.secrets.prefix";
    
    // Azure configuration
    public static final String AZURE_VAULT_URL = "azure.keyvault.vault-url";
    public static final String AZURE_TENANT_ID = "azure.keyvault.tenant-id";
    public static final String AZURE_PREFIX = "azure.keyvault.secret-prefix";
    
    // Key management
    public static final String KEY_ROTATION_ENABLED = "secret.key-rotation-enabled";
    public static final String KEY_ROTATION_DAYS = "secret.key-rotation-days";
    
    // API Key configuration
    public static final String APIKEY_HASH_SECRET = "apikey.hash.secret";
    public static final String APIKEY_RATE_LIMIT = "apikey.rate-limit.requests-per-minute";
    
    // Database configuration
    public static final String DB_URL = "quarkus.datasource.reactive.url";
    public static final String DB_USERNAME = "quarkus.datasource.username";
    public static final String DB_PASSWORD = "quarkus.datasource.password";
}

/**
 * Docker Compose service names
 */
class DockerServices {
    public static final String POSTGRES = "postgres";
    public static final String VAULT = "vault";
    public static final String LOCALSTACK = "localstack";
    public static final String WAYANG = "wayang";
}

/**
 * Kubernetes resource names
 */
class KubernetesResources {
    public static final String NAMESPACE = "wayang";
    public static final String SECRET_NAME = "wayang-secrets";
    public static final String CONFIGMAP_NAME = "wayang-config";
    public static final String DEPLOYMENT_NAME = "wayang-platform";
    public static final String SERVICE_NAME = "wayang-platform";
}

/**
 * Health check endpoints
 */
class HealthEndpoints {
    public static final String HEALTH = "/q/health";
    public static final String HEALTH_LIVE = "/q/health/live";
    public static final String HEALTH_READY = "/q/health/ready";
    public static final String SECRETS_HEALTH = "/api/v1/secrets/health";
}

/**
 * Default configuration values
 */
class Defaults {
    public static final String DEFAULT_BACKEND = "local";
    public static final String DEFAULT_ENVIRONMENT = "dev";
    public static final int DEFAULT_RETENTION_DAYS = 30;
    public static final int DEFAULT_RATE_LIMIT = 60;
    public static final boolean DEFAULT_KEY_ROTATION_ENABLED = true;
    public static final int DEFAULT_KEY_ROTATION_DAYS = 90;
    public static final String DEFAULT_VAULT_PATH = "secret";
    public static final String DEFAULT_AWS_PREFIX = "wayang/";
    public static final String DEFAULT_AZURE_PREFIX = "wayang-";
}
