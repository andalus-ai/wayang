package tech.kayys.wayang.rest;

import java.nio.file.Path;
import java.time.Clock;
import java.time.Duration;
import java.util.Optional;
import java.util.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.VaultClient;
import tech.kayys.wayang.api.VaultSecretResolver;

/**
 * HashiCorp Vault, optional. Two independent uses: the master keyring of the secret store can be fetched from it
 * ({@code wayang.secrets.vault-keyring-path}), and {@code ${secret:vault.NAME}} can be read from it at run time.
 * <pre>
 * wayang.vault.address=https://vault.example.com:8200
 * wayang.vault.namespace=team-a                      # enterprise namespaces, optional
 * wayang.vault.token-file=/run/secrets/vault-token   # or wayang.vault.token (less safe), or AppRole:
 * wayang.vault.approle.role-id=...  wayang.vault.approle.secret-id-file=/run/secrets/vault-secret-id
 * wayang.vault.mount=secret                          # KV v2 mount
 * wayang.vault.base-path=wayang                      # secrets: &lt;mount&gt;/data/&lt;base-path&gt;/&lt;tenant&gt;/&lt;name&gt;, field "value"
 * </pre>
 */
@ApplicationScoped
public class VaultConfig {

    private static final Logger LOG = Logger.getLogger("wayang.vault");

    @ConfigProperty(name = "wayang.vault.address")
    Optional<String> address;
    @ConfigProperty(name = "wayang.vault.namespace")
    Optional<String> namespace;
    @ConfigProperty(name = "wayang.vault.token")
    Optional<String> token;
    @ConfigProperty(name = "wayang.vault.token-file")
    Optional<String> tokenFile;
    @ConfigProperty(name = "wayang.vault.approle.role-id")
    Optional<String> roleId;
    @ConfigProperty(name = "wayang.vault.approle.secret-id-file")
    Optional<String> secretIdFile;
    @ConfigProperty(name = "wayang.vault.approle.mount", defaultValue = "approle")
    String approleMount;
    @ConfigProperty(name = "wayang.vault.mount", defaultValue = "secret")
    String mount;
    @ConfigProperty(name = "wayang.vault.base-path", defaultValue = "wayang")
    String basePath;
    @ConfigProperty(name = "wayang.vault.timeout-seconds", defaultValue = "5")
    int timeoutSeconds;
    @ConfigProperty(name = "wayang.vault.cache-seconds", defaultValue = "30")
    int cacheSeconds;
    @ConfigProperty(name = "wayang.vault.allow-insecure", defaultValue = "false")
    boolean allowInsecure;

    @Inject
    Clock clock;

    private volatile Optional<VaultClient> client;
    private volatile Optional<VaultSecretResolver> resolver;

    public synchronized Optional<VaultClient> client() {
        if (client == null) {
            if (address.isEmpty()) {
                client = Optional.empty();
            } else {
                Duration t = Duration.ofSeconds(Math.max(1, Math.min(60, timeoutSeconds)));
                java.util.function.Supplier<String> tokens;
                if (roleId.isPresent()) {
                    String file = secretIdFile.orElseThrow(() -> new IllegalStateException("wayang.vault.approle.secret-id-file is required with role-id"));
                    tokens = VaultClient.appRole(address.get(), namespace.orElse(null), approleMount, roleId.get(), VaultClient.tokenFile(Path.of(file)), t,
                            allowInsecure, clock);
                } else if (tokenFile.isPresent()) {
                    tokens = VaultClient.tokenFile(Path.of(tokenFile.get()));
                } else if (token.isPresent()) {
                    tokens = VaultClient.staticToken(token.get());
                } else {
                    throw new IllegalStateException("wayang.vault.address needs wayang.vault.token-file, wayang.vault.token or wayang.vault.approle.*");
                }
                client = Optional.of(new VaultClient(address.get(), namespace.orElse(null), tokens, t, allowInsecure));
            }
        }
        return client;
    }

    public String mount() {
        return mount;
    }

    public synchronized Optional<VaultSecretResolver> resolver() {
        if (resolver == null) {
            resolver = client().map(c -> new VaultSecretResolver(c, mount, basePath, Math.max(0, cacheSeconds), Math.min(5, Math.max(0, cacheSeconds)), clock,
                    msg -> LOG.warning(msg)));
        }
        return resolver;
    }
}
