package tech.kayys.wayang.rest;

import java.util.Optional;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.event.Observes;

import io.quarkus.runtime.StartupEvent;
import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.LocalAuth;

/**
 * {@code wayang.auth.mode=local}: the Community all-in-one server with one operator key ({@code wayang.auth.local.api-key}, at least
 * 24 characters; prefer a mounted secret through the usual config sources) and one tenant ({@code wayang.auth.local.tenant}, default
 * {@code local}). A bad or missing key stops the server at start-up, not at the first request. See {@link LocalAuth}.
 */
@ApplicationScoped
public class LocalAuthConfig {

    @ConfigProperty(name = "wayang.auth.mode", defaultValue = "jwt")
    String mode;

    @ConfigProperty(name = "wayang.auth.local.api-key")
    Optional<String> apiKey;

    @ConfigProperty(name = "wayang.auth.local.tenant")
    Optional<String> tenant;

    private volatile LocalAuth auth;

    void onStart(@Observes StartupEvent e) {
        if ("local".equalsIgnoreCase(mode)) {
            auth(); // fail fast
        }
    }

    public LocalAuth auth() {
        LocalAuth a = auth;
        if (a == null) {
            synchronized (this) {
                if (auth == null) {
                    auth = new LocalAuth(apiKey.orElse(null), tenant.orElse(null));
                }
                a = auth;
            }
        }
        return a;
    }
}
