package tech.kayys.wayang.rest;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.mcp.McpService;
import tech.kayys.wayang.api.mcp.McpStore;
import tech.kayys.wayang.connectors.HttpConnector;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.mcp.OpenApiExecutor;
import tech.kayys.wayang.runtime.json.Json;
import tech.kayys.wayang.store.jdbc.JdbcMcpStore;

/**
 * Generated MCP servers. Spec downloads and tool calls both go through the same URL guard as the {@code http} connector
 * ({@code wayang.connectors.http.allow-private}, {@code wayang.connectors.http.allowed-hosts}).
 */
@ApplicationScoped
public class McpConfig {

    @ConfigProperty(name = "wayang.connectors.http.allow-private", defaultValue = "false")
    boolean allowPrivate;
    @ConfigProperty(name = "wayang.connectors.http.allowed-hosts")
    Optional<List<String>> allowedHosts;
    @ConfigProperty(name = "wayang.store", defaultValue = "memory")
    String storeKind;

    @Inject
    WayangBeans beans;
    @Inject
    OAuthConfig secrets;
    @Inject
    Clock clock;

    @Inject
    tech.kayys.wayang.api.WayangApi api;
    @Inject
    tech.kayys.wayang.api.definition.DefinitionStore definitions;

    private volatile McpService service;
    private volatile tech.kayys.wayang.api.mcp.ProcessMcpService processes;

    public synchronized tech.kayys.wayang.api.mcp.ProcessMcpService processes() {
        if (processes == null) {
            processes = new tech.kayys.wayang.api.mcp.ProcessMcpService(api, definitions);
        }
        return processes;
    }

    public synchronized McpService service() {
        if (service == null) {
            UrlGuard guard = new UrlGuard(allowPrivate, Set.copyOf(allowedHosts.orElse(List.of())));
            McpStore store = "jdbc".equals(storeKind) ? new JdbcMcpStore(beans.jdbc()) : new McpStore.InMemory();
            HttpConnector http = new HttpConnector(guard);
            McpService.SpecFetcher fetcher = url -> {
                Map<String, Object> res = http.call(Map.of("url", url, "method", "GET", "timeoutMs", 20_000, "maxResponseBytes", 5_000_000,
                        "headers", Map.of("Accept", "application/json, application/yaml, text/yaml, */*")), Map.of());
                Object body = res.get("body");
                if (body instanceof String s) {
                    return s;
                }
                if (body == null) {
                    throw new java.io.IOException("Empty response");
                }
                return Json.write(body); // JSON content types arrive parsed
            };
            service = new McpService(store, fetcher, new OpenApiExecutor(guard), secrets.resolver(), clock);
        }
        return service;
    }
}
