package tech.kayys.wayang.rest;

import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.connectors.Connectors;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Built-in connectors for service tasks. The HTTP connector refuses private/loopback/link-local targets unless
 * the operator opts in:
 * <pre>
 * wayang.connectors.http.allow-private=false        # true only for dev / trusted in-cluster use
 * wayang.connectors.http.allowed-hosts=billing.internal,erp.internal
 * </pre>
 */
@ApplicationScoped
public class ConnectorsConfig {

    @ConfigProperty(name = "wayang.connectors.http.allow-private", defaultValue = "false")
    boolean allowPrivate;
    @ConfigProperty(name = "wayang.connectors.http.allowed-hosts")
    Optional<List<String>> allowedHosts;

    private static tech.kayys.wayang.runtime.Extensions extensions() {
        return tech.kayys.wayang.runtime.Extensions.load(tech.kayys.wayang.runtime.Profile.fromEnvironment());
    }

    private volatile Map<String, ServiceHandler.Connector> all;

    private volatile Map<String, tech.kayys.wayang.runtime.Poller> pollers;

    public Map<String, tech.kayys.wayang.runtime.Poller> pollers() {
        Map<String, tech.kayys.wayang.runtime.Poller> p = pollers;
        if (p == null) {
            p = pollers = Connectors.pollers(new tech.kayys.wayang.connectors.UrlGuard(allowPrivate, Set.copyOf(allowedHosts.orElse(List.of()))), extensions());
        }
        return p;
    }

    public Map<String, tech.kayys.wayang.runtime.WebhookProvider> webhookProviders() {
        return Connectors.webhookProviders(extensions());
    }

    public Map<String, ServiceHandler.Connector> all() {
        Map<String, ServiceHandler.Connector> a = all;
        if (a == null) {
            a = all = Connectors.defaults(new tech.kayys.wayang.connectors.UrlGuard(allowPrivate, Set.copyOf(allowedHosts.orElse(List.of()))), extensions());
        }
        return a;
    }
}
