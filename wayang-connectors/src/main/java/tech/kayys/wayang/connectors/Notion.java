package tech.kayys.wayang.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code notion}: add rows to a database, append text to a page, read a database.
 *
 * <p>Config: {@code token} (integration secret as {@code ${secret:NAME}}), {@code operation}, optional {@code baseUrl}.
 * Allow-lists are required: {@code allowedDatabases} for {@code createPage} / {@code queryDatabase}, {@code allowedPages} for
 * {@code appendText}. Ids are UUIDs (dashes optional).
 * <ul>
 * <li>{@code createPage}: {@code databaseId}, {@code title}, optional {@code titleProperty} (default Name), {@code text}
 * (paragraph blocks), {@code properties} (raw Notion property objects, merged, never replacing the title).</li>
 * <li>{@code appendText}: {@code pageId}, {@code text}.</li>
 * <li>{@code queryDatabase}: {@code databaseId}, optional {@code filter} (raw Notion filter), {@code limit} (1..50).
 * Output {@code results: [{id, url, title, properties}]} with {@code title} read from the title property.</li>
 * </ul>
 */
public final class Notion {

    private static final Pattern ID = Pattern.compile("[0-9a-fA-F]{8}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{4}-?[0-9a-fA-F]{12}");
    private static final Pattern PROP = Pattern.compile("[^\\p{Cntrl}]{1,100}");
    private static final String DEFAULT_BASE = "https://api.notion.com";
    private static final int CHUNK = 2000;
    private static final int MAX_BLOCKS = 90;

    private Notion() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("notion needs node configuration", false);
        }

        @Override
        @SuppressWarnings("unchecked")
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String token = Rest.secret(config, "token", "notion", 20);
            Map<String, String> h = Map.of("Authorization", "Bearer " + token, "Notion-Version", "2022-06-28");
            String base = Rest.base(config, DEFAULT_BASE);
            String op = config.get("operation") instanceof String o ? o : "";
            switch (op) {
                case "createPage" -> {
                    String db = id(inputs.get("databaseId"), "databaseId");
                    Rest.allowed(norm(config.get("allowedDatabases")), db, "database");
                    String titleProp = inputs.get("titleProperty") instanceof String t ? Rest.segment(t, PROP, "titleProperty") : "Name";
                    Map<String, Object> props = new LinkedHashMap<>();
                    if (inputs.get("properties") instanceof Map<?, ?> m) {
                        if (m.size() > 50) {
                            throw new ServiceHandler.ConnectorException("Too many properties", false);
                        }
                        m.forEach((k, v) -> props.put(String.valueOf(k), v));
                    } else if (inputs.get("properties") != null) {
                        throw new ServiceHandler.ConnectorException("properties must be an object", false);
                    }
                    String title = Rest.text(inputs.get("title"), "title", 2000, true);
                    props.put(titleProp, Map.of("title", List.of(richText(title))));
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("parent", Map.of("database_id", db));
                    body.put("properties", props);
                    String text = Rest.text(inputs.get("text"), "text", 100_000, false);
                    if (text != null) {
                        body.put("children", paragraphs(text));
                    }
                    Map<String, Object> r = Rest.call(http, base + "/v1/pages", "POST", h, null, body);
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("pageId", r.get("id"));
                    out.put("url", r.get("url"));
                    return out;
                }
                case "appendText" -> {
                    String page = id(inputs.get("pageId"), "pageId");
                    Rest.allowed(norm(config.get("allowedPages")), page, "page");
                    Map<String, Object> r = Rest.call(http, base + "/v1/blocks/" + page + "/children", "PATCH", h, null,
                            Map.of("children", paragraphs(Rest.text(inputs.get("text"), "text", 100_000, true))));
                    return Map.of("appended", r.get("results") instanceof List<?> l ? l.size() : 0);
                }
                case "queryDatabase" -> {
                    String db = id(inputs.get("databaseId"), "databaseId");
                    Rest.allowed(norm(config.get("allowedDatabases")), db, "database");
                    Map<String, Object> body = new LinkedHashMap<>();
                    body.put("page_size", Rest.limit(inputs.get("limit"), 20, 50));
                    if (inputs.get("filter") instanceof Map<?, ?> f) {
                        body.put("filter", f);
                    } else if (inputs.get("filter") != null) {
                        throw new ServiceHandler.ConnectorException("filter must be an object", false);
                    }
                    Map<String, Object> r = Rest.call(http, base + "/v1/databases/" + db + "/query", "POST", h, null, body);
                    List<Map<String, Object>> out = new ArrayList<>();
                    if (r.get("results") instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof Map<?, ?> p) {
                                Map<String, Object> e = new LinkedHashMap<>();
                                e.put("id", p.get("id"));
                                e.put("url", p.get("url"));
                                e.put("title", titleOf((Map<String, Object>) p.get("properties")));
                                e.put("properties", p.get("properties"));
                                out.add(e);
                            }
                        }
                    }
                    return Map.of("results", out, "count", out.size(), "hasMore", Boolean.TRUE.equals(r.get("has_more")));
                }
                default -> throw new ServiceHandler.ConnectorException("notion 'operation' must be createPage, appendText or queryDatabase", false);
            }
        }

        private static String id(Object v, String what) throws ServiceHandler.ConnectorException {
            return Rest.segment(v, ID, what).replace("-", "").toLowerCase(java.util.Locale.ROOT);
        }

        private static List<String> norm(Object list) {
            List<String> out = new ArrayList<>();
            if (list instanceof List<?> l) {
                for (Object o : l) {
                    out.add(String.valueOf(o).replace("-", "").toLowerCase(java.util.Locale.ROOT));
                }
            }
            return out;
        }

        private static Map<String, Object> richText(String s) {
            return Map.of("type", "text", "text", Map.of("content", s));
        }

        /** One paragraph block per blank-line-separated chunk, each at most 2000 characters (Notion's limit). */
        private static List<Map<String, Object>> paragraphs(String text) throws ServiceHandler.ConnectorException {
            List<Map<String, Object>> blocks = new ArrayList<>();
            for (String para : text.split("\\n\\s*\\n")) {
                String p = para.strip();
                for (int i = 0; i < p.length(); i += CHUNK) {
                    if (blocks.size() >= MAX_BLOCKS) {
                        throw new ServiceHandler.ConnectorException("Text needs more than " + MAX_BLOCKS + " blocks", false);
                    }
                    String part = p.substring(i, Math.min(p.length(), i + CHUNK));
                    blocks.add(Map.of("object", "block", "type", "paragraph", "paragraph", Map.of("rich_text", List.of(richText(part)))));
                }
            }
            return blocks;
        }

        private static String titleOf(Map<String, Object> props) {
            if (props == null) {
                return null;
            }
            for (Object v : props.values()) {
                if (v instanceof Map<?, ?> m && "title".equals(m.get("type")) && m.get("title") instanceof List<?> l) {
                    StringBuilder sb = new StringBuilder();
                    for (Object o : l) {
                        if (o instanceof Map<?, ?> t && t.get("plain_text") != null) {
                            sb.append(t.get("plain_text"));
                        }
                    }
                    return sb.toString();
                }
            }
            return null;
        }
    }
}
