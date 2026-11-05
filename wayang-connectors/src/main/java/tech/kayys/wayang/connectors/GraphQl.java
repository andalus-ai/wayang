package tech.kayys.wayang.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code graphql}: one query or mutation over HTTP POST.
 *
 * <p>Config: {@code url}, {@code headers} (secrets as {@code ${secret:NAME}}), <b>{@code query}</b> (the GraphQL document, fixed in the
 * definition; at most 100,000 characters), optional {@code operationName}, {@code failOnErrors} (default true: a non-empty {@code errors}
 * list fails the node), {@code outputMap} (variable to path read from the result), {@code timeoutMs}. The node's {@code inputs} are the
 * <b>variables</b>: values never get spliced into the document. A model-driven node may set {@code allowDynamicQuery: true} to let the
 * input {@code query} (and {@code variables}) replace the fixed document; leave it off unless the model is meant to write queries.
 * Subscriptions are refused (this is request/response). Output: {@code data}, {@code errors}, and the outputMap variables.
 */
public final class GraphQl {

    private static final int MAX_QUERY = 100_000;
    private static final Pattern SUBSCRIPTION = Pattern.compile("(?s)\\A(?:\\s|#[^\\n]*\\n?)*subscription\\b.*");

    private GraphQl() {
    }

    public static final class Client implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Client(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("graphql needs node configuration (url, query)", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String url = Proto.url(config, "graphql");
            Map<String, Object> vars = inputs == null ? Map.of() : inputs;
            String query = config.get("query") instanceof String q ? q : null;
            if (Boolean.TRUE.equals(config.get("allowDynamicQuery")) && vars.get("query") instanceof String dq) {
                query = dq;
                vars = vars.get("variables") instanceof Map<?, ?> v ? castMap(v) : Map.of();
            }
            if (query == null || query.isBlank()) {
                throw new ServiceHandler.ConnectorException("graphql needs a 'query' in the node configuration", false);
            }
            if (query.length() > MAX_QUERY) {
                throw new ServiceHandler.ConnectorException("graphql query is longer than " + MAX_QUERY + " characters", false);
            }
            if (SUBSCRIPTION.matcher(query).matches()) {
                throw new ServiceHandler.ConnectorException("graphql subscriptions are not supported (request/response only)", false);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("query", query);
            if (!vars.isEmpty()) {
                body.put("variables", vars);
            }
            if (config.get("operationName") instanceof String on && !on.isBlank()) {
                body.put("operationName", on);
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("url", url);
            cfg.put("method", "POST");
            cfg.put("timeoutMs", config.getOrDefault("timeoutMs", 30_000));
            cfg.put("returnErrorBody", true);
            Map<String, String> h = Proto.headers(config);
            h.put("Accept", "application/graphql-response+json, application/json");
            cfg.put("headers", h);
            Map<String, Object> res = http.call(cfg, Map.of("body", body));
            int status = res.get("status") instanceof Number n ? n.intValue() : 0;
            Object b = res.get("body");
            Map<String, Object> out = new LinkedHashMap<>();
            if (b instanceof Map<?, ?> m && (m.containsKey("data") || m.containsKey("errors"))) {
                out.put("data", m.get("data"));
                List<Object> errors = m.get("errors") instanceof List<?> l ? new ArrayList<>(l) : List.of();
                out.put("errors", errors);
                if (!errors.isEmpty() && !Boolean.FALSE.equals(config.get("failOnErrors"))) {
                    Object first = errors.get(0) instanceof Map<?, ?> em ? em.get("message") : errors.get(0);
                    throw new ServiceHandler.ConnectorException("GraphQL error: " + Proto.clip(first, 300)
                            + (errors.size() > 1 ? " (+" + (errors.size() - 1) + " more)" : ""), false);
                }
            } else {
                throw new ServiceHandler.ConnectorException("The GraphQL endpoint answered HTTP " + status + " without a GraphQL result",
                        Proto.retryableStatus(status));
            }
            Proto.outputMap(config, out, out);
            return out;
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castMap(Map<?, ?> m) {
            return (Map<String, Object>) m;
        }
    }
}
