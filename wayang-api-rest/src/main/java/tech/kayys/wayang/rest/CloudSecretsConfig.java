package tech.kayys.wayang.rest;

import java.net.URI;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;
import java.util.logging.Logger;
import java.util.function.Supplier;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.AwsCredentialSources;
import tech.kayys.wayang.api.AwsSecretsManager;
import tech.kayys.wayang.api.AwsSigV4;
import tech.kayys.wayang.api.AzureKeyVault;
import tech.kayys.wayang.api.CloudHttp;
import tech.kayys.wayang.api.CloudSecretResolver;
import tech.kayys.wayang.api.CloudTokens;
import tech.kayys.wayang.api.GcpSecretManager;
import tech.kayys.wayang.runtime.SecretResolver;

/**
 * Cloud secret services, each optional and alongside Wayang's own store: {@code ${secret:aws.NAME}},
 * {@code ${secret:azure.NAME}}, {@code ${secret:gcp.NAME}}. Wayang only reads; the tenant is part of every id. See
 * {@code docs/WORKSPACE.md}. Credentials come from files or the standard environment, never from a run or a request.
 * <pre>
 * wayang.cloud.cache-seconds=30   wayang.cloud.timeout-seconds=5   wayang.cloud.allow-insecure=false
 * # AWS Secrets Manager: secret id is &lt;base-path&gt;/&lt;tenant&gt;/&lt;name&gt;
 * wayang.cloud.aws.region=eu-west-1
 * wayang.cloud.aws.access-key-id=AKIA...   wayang.cloud.aws.secret-access-key-file=/run/secrets/aws-secret   (or the AWS_* env vars)
 * # Azure Key Vault (Azure AD app): secret name is [base--]tenant--name
 * wayang.cloud.azure.vault-url=https://myvault.vault.azure.net
 * wayang.cloud.azure.tenant-id=...  wayang.cloud.azure.client-id=...  wayang.cloud.azure.client-secret-file=/run/secrets/azure-secret
 * # Google Secret Manager (service-account key): secret id is [base--]tenant--name
 * wayang.cloud.gcp.project=my-project  wayang.cloud.gcp.service-account-file=/run/secrets/gcp-sa.json
 * </pre>
 */
@ApplicationScoped
public class CloudSecretsConfig {

    private static final Logger LOG = Logger.getLogger("wayang.cloud");

    @ConfigProperty(name = "wayang.cloud.cache-seconds", defaultValue = "30")
    int cacheSeconds;
    @ConfigProperty(name = "wayang.cloud.timeout-seconds", defaultValue = "5")
    int timeoutSeconds;
    @ConfigProperty(name = "wayang.cloud.allow-insecure", defaultValue = "false")
    boolean allowInsecure;

    /** static (default: keys from config/files/env) | imds (EC2 instance role) | container (ECS/Fargate task role). */
    @ConfigProperty(name = "wayang.cloud.aws.credentials", defaultValue = "static")
    String awsCredentials;
    @ConfigProperty(name = "wayang.cloud.aws.imds-endpoint", defaultValue = "http://169.254.169.254")
    String awsImds;
    @ConfigProperty(name = "wayang.cloud.aws.region")
    Optional<String> awsRegion;
    @ConfigProperty(name = "wayang.cloud.aws.endpoint")
    Optional<String> awsEndpoint;
    @ConfigProperty(name = "wayang.cloud.aws.base-path", defaultValue = "wayang")
    String awsBase;
    @ConfigProperty(name = "wayang.cloud.aws.access-key-id")
    Optional<String> awsKeyId;
    @ConfigProperty(name = "wayang.cloud.aws.secret-access-key-file")
    Optional<String> awsSecretFile;
    @ConfigProperty(name = "wayang.cloud.aws.secret-access-key")
    Optional<String> awsSecret;
    @ConfigProperty(name = "wayang.cloud.aws.session-token-file")
    Optional<String> awsTokenFile;

    /** client-secret (default) | managed-identity (VM/AKS IMDS, or App Service when IDENTITY_ENDPOINT is set). */
    @ConfigProperty(name = "wayang.cloud.azure.auth", defaultValue = "client-secret")
    String azureAuth;
    @ConfigProperty(name = "wayang.cloud.azure.imds-endpoint", defaultValue = "http://169.254.169.254/metadata/identity/oauth2/token")
    String azureImds;
    @ConfigProperty(name = "wayang.cloud.azure.vault-url")
    Optional<String> azureVault;
    @ConfigProperty(name = "wayang.cloud.azure.tenant-id")
    Optional<String> azureTenant;
    @ConfigProperty(name = "wayang.cloud.azure.client-id")
    Optional<String> azureClient;
    @ConfigProperty(name = "wayang.cloud.azure.client-secret-file")
    Optional<String> azureSecretFile;
    @ConfigProperty(name = "wayang.cloud.azure.authority", defaultValue = "https://login.microsoftonline.com")
    String azureAuthority;
    @ConfigProperty(name = "wayang.cloud.azure.base-path", defaultValue = "wayang")
    String azureBase;

    /** service-account (default: key file) | metadata (GCE/GKE/Cloud Run attached account). */
    @ConfigProperty(name = "wayang.cloud.gcp.auth", defaultValue = "service-account")
    String gcpAuth;
    @ConfigProperty(name = "wayang.cloud.gcp.metadata-endpoint", defaultValue = "http://metadata.google.internal")
    String gcpMetadata;
    @ConfigProperty(name = "wayang.cloud.gcp.project")
    Optional<String> gcpProject;
    @ConfigProperty(name = "wayang.cloud.gcp.service-account-file")
    Optional<String> gcpKeyFile;
    @ConfigProperty(name = "wayang.cloud.gcp.endpoint")
    Optional<String> gcpEndpoint;
    @ConfigProperty(name = "wayang.cloud.gcp.token-uri", defaultValue = "https://oauth2.googleapis.com/token")
    String gcpTokenUri;
    @ConfigProperty(name = "wayang.cloud.gcp.base-path", defaultValue = "wayang")
    String gcpBase;

    @Inject
    Clock clock;

    private volatile Map<String, SecretResolver> resolvers;

    /**
     * The platform's own credential endpoints (link-local, plain http by design). Operator-set only; the scheme must still be
     * http(s) with a host, but https is not demanded here.
     */
    private static URI platformEndpoint(String url, String what) {
        return CloudHttp.endpoint(url, true, what);
    }

    private Supplier<AwsSigV4.Credentials> containerCredentials(CloudHttp http) {
        String rel = System.getenv("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI");
        String full = System.getenv("AWS_CONTAINER_CREDENTIALS_FULL_URI");
        URI uri;
        if (rel != null && !rel.isBlank()) {
            if (!rel.startsWith("/") || rel.contains("..") || rel.contains("?") || rel.contains("#")) {
                throw new IllegalStateException("AWS_CONTAINER_CREDENTIALS_RELATIVE_URI is not a plain path");
            }
            uri = URI.create("http://169.254.170.2" + rel);
        } else if (full != null && !full.isBlank()) {
            uri = URI.create(full);
        } else {
            throw new IllegalStateException("wayang.cloud.aws.credentials=container needs AWS_CONTAINER_CREDENTIALS_RELATIVE_URI or _FULL_URI");
        }
        String tokenFile = System.getenv("AWS_CONTAINER_AUTHORIZATION_TOKEN_FILE");
        Supplier<String> auth = tokenFile != null && !tokenFile.isBlank() ? CloudTokens.fileText(Path.of(tokenFile)) : () -> System.getenv("AWS_CONTAINER_AUTHORIZATION_TOKEN");
        return AwsCredentialSources.container(uri, auth, http, clock);
    }

    /** Prefix (e.g. {@code aws.}) to resolver, for the clouds that are configured; empty when none is. */
    public synchronized Map<String, SecretResolver> resolvers() {
        if (resolvers == null) {
            Map<String, SecretResolver> m = new LinkedHashMap<>();
            Duration t = Duration.ofSeconds(Math.max(1, Math.min(60, timeoutSeconds)));
            CloudHttp http = new CloudHttp(t);
            long hit = Math.max(0, cacheSeconds);
            long miss = Math.min(5, hit);
            if (awsRegion.isPresent()) {
                Supplier<AwsSigV4.Credentials> creds = switch (awsCredentials) {
                case "imds" -> AwsCredentialSources.imds(platformEndpoint(awsImds, "wayang.cloud.aws.imds-endpoint"), http, clock);
                case "container" -> containerCredentials(http);
                case "static" -> () -> new AwsSigV4.Credentials(
                        awsKeyId.orElseGet(() -> System.getenv("AWS_ACCESS_KEY_ID")),
                        awsSecretFile.map(f -> CloudTokens.fileText(Path.of(f)).get().strip()).or(() -> awsSecret).orElseGet(() -> System.getenv("AWS_SECRET_ACCESS_KEY")),
                        awsTokenFile.map(f -> CloudTokens.fileText(Path.of(f)).get().strip()).orElseGet(() -> System.getenv("AWS_SESSION_TOKEN")));
                default -> throw new IllegalStateException("wayang.cloud.aws.credentials must be static, imds or container");
                };
                m.put("aws.", new CloudSecretResolver("aws.", new AwsSecretsManager(awsRegion.get(),
                        awsEndpoint.map(e -> CloudHttp.endpoint(e, allowInsecure, "wayang.cloud.aws.endpoint")).orElse(null), awsBase, creds, http, clock), hit, miss, clock, LOG::warning));
            }
            if (azureVault.isPresent()) {
                Supplier<String> token;
                if ("managed-identity".equals(azureAuth)) {
                    String appEndpoint = System.getenv("IDENTITY_ENDPOINT");
                    String appHeader = System.getenv("IDENTITY_HEADER");
                    boolean app = appEndpoint != null && !appEndpoint.isBlank() && appHeader != null && !appHeader.isBlank();
                    token = CloudTokens.azureManagedIdentity(platformEndpoint(app ? appEndpoint : azureImds, "wayang.cloud.azure.imds-endpoint"),
                            app ? appHeader : null, azureClient.orElse(null), http, clock);
                } else if ("client-secret".equals(azureAuth)) {
                    String secretFile = azureSecretFile.orElseThrow(() -> new IllegalStateException("wayang.cloud.azure.client-secret-file is required with vault-url"));
                    token = CloudTokens.azureClientCredentials(CloudHttp.endpoint(azureAuthority, allowInsecure, "wayang.cloud.azure.authority"),
                            azureTenant.orElseThrow(() -> new IllegalStateException("wayang.cloud.azure.tenant-id is required")),
                            azureClient.orElseThrow(() -> new IllegalStateException("wayang.cloud.azure.client-id is required")),
                            CloudTokens.fileText(Path.of(secretFile)), http, clock);
                } else {
                    throw new IllegalStateException("wayang.cloud.azure.auth must be client-secret or managed-identity");
                }
                m.put("azure.", new CloudSecretResolver("azure.", new AzureKeyVault(
                        CloudHttp.endpoint(azureVault.get(), allowInsecure, "wayang.cloud.azure.vault-url"), azureBase, token, http), hit, miss, clock, LOG::warning));
            }
            if (gcpProject.isPresent()) {
                Supplier<String> token;
                if ("metadata".equals(gcpAuth)) {
                    token = CloudTokens.gcpMetadata(platformEndpoint(gcpMetadata, "wayang.cloud.gcp.metadata-endpoint"), http, clock);
                } else if ("service-account".equals(gcpAuth)) {
                    String keyFile = gcpKeyFile.orElseThrow(() -> new IllegalStateException("wayang.cloud.gcp.service-account-file is required with project"));
                    token = CloudTokens.gcpServiceAccount(CloudTokens.fileText(Path.of(keyFile)),
                            CloudHttp.endpoint(gcpTokenUri, allowInsecure, "wayang.cloud.gcp.token-uri"), http, clock);
                } else {
                    throw new IllegalStateException("wayang.cloud.gcp.auth must be service-account or metadata");
                }
                m.put("gcp.", new CloudSecretResolver("gcp.", new GcpSecretManager(
                        gcpEndpoint.map(e -> CloudHttp.endpoint(e, allowInsecure, "wayang.cloud.gcp.endpoint")).orElse(null), gcpProject.get(), gcpBase, token, http),
                        hit, miss, clock, LOG::warning));
            }
            resolvers = m;
        }
        return resolvers;
    }
}
