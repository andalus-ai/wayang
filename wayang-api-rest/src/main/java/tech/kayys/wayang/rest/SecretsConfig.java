package tech.kayys.wayang.rest;

import java.time.Clock;
import java.util.Base64;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.secret.InMemorySecretStore;
import tech.kayys.wayang.api.secret.SecretCipher;
import tech.kayys.wayang.api.secret.SecretService;
import tech.kayys.wayang.api.secret.SecretStore;
import tech.kayys.wayang.store.jdbc.JdbcSecretStore;

/**
 * Secrets are enabled only when a master key is configured:
 * <pre>
 * wayang.secrets.key-id=k1
 * wayang.secrets.key=&lt;base64 of 32 random bytes&gt;          # e.g. openssl rand -base64 32
 * wayang.secrets.old-keys=k0:&lt;base64&gt;,...                  # kept only to read/re-seal older secrets
 * </pre>
 * The key should come from the environment or a mounted secret, never from a committed file. Without a key
 * {@code ${secret:NAME}} fails closed and the secret endpoints answer 404.
 */
@ApplicationScoped
public class SecretsConfig {

    @ConfigProperty(name = "wayang.secrets.key")
    Optional<String> key;
    @ConfigProperty(name = "wayang.secrets.key-id", defaultValue = "k1")
    String keyId;
    @ConfigProperty(name = "wayang.secrets.key-file")
    Optional<String> keyFile;
    @ConfigProperty(name = "wayang.secrets.key-file-allow-loose-permissions", defaultValue = "false")
    boolean keyFileLoose;
    @ConfigProperty(name = "wayang.secrets.vault-keyring-path")
    Optional<String> vaultKeyringPath;
    @ConfigProperty(name = "wayang.secrets.archive-enabled", defaultValue = "false")
    boolean archiveEnabled;
    @ConfigProperty(name = "wayang.secrets.old-keys")
    Optional<List<String>> oldKeys;
    @ConfigProperty(name = "wayang.store", defaultValue = "memory")
    String storeKind;

    @Inject
    WayangBeans beans;
    @Inject
    VaultConfig vault;
    @Inject
    Clock clock;

    private volatile Optional<SecretService> service;

    /** Whether the encrypted secrets archive endpoints are on (off by default: they are the one place plaintext leaves on purpose). */
    public boolean archiveEnabled() {
        return archiveEnabled;
    }

    /** The master keys come from exactly one place: a mounted file, Vault, or (least safe) the configuration itself. */
    private SecretCipher cipher() {
        int sources = (key.isPresent() ? 1 : 0) + (keyFile.isPresent() ? 1 : 0) + (vaultKeyringPath.isPresent() ? 1 : 0);
        if (sources == 0) {
            return null;
        }
        if (sources > 1) {
            throw new IllegalStateException("Set only one of wayang.secrets.key, wayang.secrets.key-file, wayang.secrets.vault-keyring-path");
        }
        if (keyFile.isPresent()) {
            return tech.kayys.wayang.api.secret.Keyring.fromFile(java.nio.file.Path.of(keyFile.get()), keyFileLoose).cipher();
        }
        if (vaultKeyringPath.isPresent()) {
            var c = vault.client().orElseThrow(() -> new IllegalStateException("wayang.secrets.vault-keyring-path needs wayang.vault.address"));
            Map<String, String> data = c.readKv2(vault.mount(), vaultKeyringPath.get());
            StringBuilder text = new StringBuilder();
            data.forEach((k, v) -> text.append(k.equals("active") ? "active=" : k + ":").append(v).append('\n'));
            return tech.kayys.wayang.api.secret.Keyring.parse(text.toString()).cipher();
        }
        Map<String, byte[]> ring = new HashMap<>();
        ring.put(keyId, Base64.getDecoder().decode(key.get().trim()));
        for (String e : oldKeys.orElse(List.of())) {
            int i = e.indexOf(':');
            if (i <= 0) {
                throw new IllegalStateException("wayang.secrets.old-keys entries must be id:base64");
            }
            ring.put(e.substring(0, i).trim(), Base64.getDecoder().decode(e.substring(i + 1).trim()));
        }
        return new SecretCipher(ring, keyId);
    }

    /** Re-reads the keyring from its file or Vault (null when the key comes from wayang.secrets.key, which only a restart changes). */
    public java.util.function.Supplier<SecretCipher> reloadSource() {
        return () -> keyFile.isPresent() || vaultKeyringPath.isPresent() ? cipher() : null;
    }

    public synchronized Optional<SecretService> service() {
        if (service == null) {
            SecretCipher cipher = cipher();
            if (cipher == null) {
                service = Optional.empty();
            } else {
                SecretStore store = "jdbc".equals(storeKind) ? new JdbcSecretStore(beans.jdbc()) : new InMemorySecretStore();
                service = Optional.of(new SecretService(store, cipher, clock));
            }
        }
        return service;
    }
}
