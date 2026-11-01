package tech.kayys.wayang.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code trello}: cards on allowed lists. Credentials go in the {@code Authorization} header, not the URL.
 *
 * <p>Config: {@code apiKey}, {@code token} (both as {@code ${secret:NAME}}), {@code operation}, <b>{@code allowedLists} (required,
 * list ids)</b>, optional {@code baseUrl}. {@code createCard} (listId, name, desc?), {@code listCards} (listId, limit?).
 */
public final class Trello {

    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{24}");
    private static final String DEFAULT_BASE = "https://api.trello.com/1";

    private Trello() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("trello needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String key = Rest.secret(config, "apiKey", "trello", 8);
            String token = Rest.secret(config, "token", "trello", 8);
            if (key.contains("\"") || token.contains("\"")) {
                throw new ServiceHandler.ConnectorException("Invalid trello credentials", false);
            }
            Map<String, String> h = Map.of("Authorization", "OAuth oauth_consumer_key=\"" + key + "\", oauth_token=\"" + token + "\"");
            String base = Rest.base(config, DEFAULT_BASE);
            String list = Rest.segment(inputs.get("listId"), ID, "listId");
            Rest.allowed(config.get("allowedLists"), list, "list");
            String op = config.get("operation") instanceof String o ? o : "";
            switch (op) {
                case "createCard" -> {
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("idList", list);
                    body.put("name", Rest.text(inputs.get("name"), "name", 16_384, true));
                    String d = Rest.text(inputs.get("desc"), "desc", 16_384, false);
                    if (d != null) {
                        body.put("desc", d);
                    }
                    Map<String, Object> r = Rest.call(http, base + "/cards", "POST", h, null, body);
                    return Map.of("id", String.valueOf(r.get("id")), "url", String.valueOf(r.get("shortUrl")));
                }
                case "listCards" -> {
                    Map<String, Object> r = Rest.call(http, base + "/lists/" + list + "/cards", "GET", h, Map.of("fields", "name,shortUrl,desc"), null);
                    int limit = Rest.limit(inputs.get("limit"), 50, 200);
                    List<Object> out = new ArrayList<>();
                    if (r.get("items") instanceof List<?> l) {
                        l.stream().limit(limit).forEach(out::add);
                    }
                    return Map.of("cards", out, "count", out.size());
                }
                default -> throw new ServiceHandler.ConnectorException("trello 'operation' must be createCard or listCards", false);
            }
        }
    }
}
