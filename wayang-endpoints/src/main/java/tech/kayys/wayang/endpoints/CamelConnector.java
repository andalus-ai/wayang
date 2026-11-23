package tech.kayys.wayang.endpoints;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.connectors.HttpConnector;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code camel}: calls a route of an Apache Camel runtime (Camel Main, Camel Quarkus, Camel K / Kamelets, JBang) that is
 * exposed over HTTP (for example with {@code platform-http}, {@code rest} DSL or a Kamelet bound to an HTTP source). Wayang embeds
 * no Camel code: the route is written in Camel's own DSL (YAML, XML, Java) and runs in its own process; this step only sends it a
 * message and returns the answer.
 *
 * <p>Node config: {@code baseUrl} (required, http/https, same SSRF guard as {@code http}; a private address needs
 * {@code WAYANG_CONNECTORS_ALLOW_PRIVATE=true}), {@code route} (path below the base URL, for example {@code orders/enrich}),
 * {@code method} (default POST), {@code headers} (for example an {@code Authorization} header as {@code ${secret:NAME}}),
 * {@code timeoutMs}, {@code maxResponseBytes}. Inputs: {@code body}, {@code headers} (become Camel message headers, as the Camel HTTP
 * consumer maps them). Output: as {@code http} ({@code status}, {@code ok}, {@code body}, {@code contentType}, {@code headers}).
 * Retry rules are those of {@code http}.
 */
final class CamelConnector implements ServiceHandler.Connector {

    private static final Pattern ROUTE = Pattern.compile("[A-Za-z0-9._~-]+(/[A-Za-z0-9._~-]+)*");

    private final HttpConnector http;

    CamelConnector(UrlGuard guard) {
        this.http = new HttpConnector(guard);
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(ConnectorInput.optional("body", "any", "The message body sent to the route (object or array as JSON, or text)"),
                ConnectorInput.optional("headers", "object", "Message headers for the route"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("camel needs node configuration (baseUrl)", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        if (!(config.get("baseUrl") instanceof String base) || base.isBlank()) {
            throw new ServiceHandler.ConnectorException("camel needs a 'baseUrl' in the node configuration", false);
        }
        String url = base.endsWith("/") ? base.substring(0, base.length() - 1) : base;
        Object route = config.get("route");
        if (route != null) {
            String r = String.valueOf(route);
            if (r.startsWith("/")) {
                r = r.substring(1);
            }
            if (!r.isEmpty()) {
                if (!ROUTE.matcher(r).matches() || r.contains("..")) {
                    throw new ServiceHandler.ConnectorException("'route' must be a plain path such as orders/enrich", false);
                }
                url = url + "/" + r;
            }
        }
        Map<String, Object> cfg = new LinkedHashMap<>(config);
        cfg.remove("baseUrl");
        cfg.remove("route");
        cfg.put("url", url);
        cfg.putIfAbsent("method", "POST");
        cfg.remove("returnErrorBody");
        return http.call(cfg, inputs);
    }
}
