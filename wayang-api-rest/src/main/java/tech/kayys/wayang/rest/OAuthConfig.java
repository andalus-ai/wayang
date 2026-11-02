package tech.kayys.wayang.rest;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.oauth.OAuthService;
import tech.kayys.wayang.api.oauth.OAuthStore;
import tech.kayys.wayang.connectors.OAuthHttp;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.store.jdbc.JdbcOAuthStore;

/**
 * Connected accounts (Gmail) are enabled when secrets are enabled and a redirect URI is configured:
 * <pre>
 * wayang.oauth.redirect-uri=https://wayang.example.com/oauth/callback   # exactly as registered with the provider
 * </pre>
 * Runtime secret resolution goes through {@link #resolver()} so {@code ${secret:oauth.NAME}} yields a fresh token.
 */
@ApplicationScoped
public class OAuthConfig {

    @ConfigProperty(name = "wayang.oauth.redirect-uri")
    Optional<String> redirectUri;
    @ConfigProperty(name = "wayang.store", defaultValue = "memory")
    String storeKind;

    @Inject
    SecretsConfig secrets;
    @Inject
    VaultConfig vault;
    @Inject
    CloudSecretsConfig cloud;

    /** Directory of mounted secrets, laid out as {@code <dir>/<tenant>/<name>}: {@code ${secret:file.NAME}}. Unset = off. */
    @org.eclipse.microprofile.config.inject.ConfigProperty(name = "wayang.secrets.file-dir")
    java.util.Optional<String> fileDir;
    @Inject
    WayangBeans beans;
    @Inject
    Clock clock;

    private volatile Optional<OAuthService> service;

    public synchronized Optional<OAuthService> service() {
        if (service == null) {
            if (redirectUri.isEmpty() || secrets.service().isEmpty()) {
                service = Optional.empty();
            } else {
                OAuthStore store = "jdbc".equals(storeKind) ? new JdbcOAuthStore(beans.jdbc()) : new OAuthStore.InMemory();
                // The token endpoint is always the public provider, so the strict guard applies regardless of the http connector's opt-ins.
                service = Optional.of(new OAuthService(store, secrets.service().get(), new OAuthHttp(UrlGuard.strict()),
                        List.of(OAuthService.Provider.google()), redirectUri.get().trim(), clock));
            }
        }
        return service;
    }

    /** What runs and triggers use to resolve ${secret:...}: the OAuth-aware resolver when configured, else plain secrets. */
    public SecretResolver resolver() {
        SecretResolver local = service().<SecretResolver>map(s -> s).orElseGet(() -> secrets.service().orElse(null));
        // each external prefix is read from its own source when that is configured: vault., file., aws., azure., gcp.
        java.util.Map<String, SecretResolver> by = new java.util.LinkedHashMap<>(cloud.resolvers());
        vault.resolver().ifPresent(v -> by.put("vault.", v));
        fileDir.ifPresent(d -> by.put("file.", new tech.kayys.wayang.api.secret.FileSecretResolver(java.nio.file.Path.of(d),
                m -> java.util.logging.Logger.getLogger("wayang.secrets").warning(m))));
        return by.isEmpty() ? local : tech.kayys.wayang.api.secret.ExternalSecrets.routing(local, by);
    }

}
