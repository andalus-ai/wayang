package tech.kayys.wayang.connectors;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code airtable}: create, update and list records.
 *
 * <p>Config: {@code token} (personal access token as {@code ${secret:NAME}}), {@code operation}, <b>{@code allowedTables}
 * (required: {@code appXXXX/Table name}, or {@code appXXXX/*})</b>, optional {@code baseUrl}. Inputs: {@code baseId},
 * {@code table} (name or {@code tbl...} id), plus
 * <ul>
 * <li>{@code createRecord}: {@code fields} (object).</li>
 * <li>{@code updateRecord}: {@code recordId}, {@code fields} (only the given fields change).</li>
 * <li>{@code listRecords}: optional {@code filterByFormula}, {@code limit} (1..100). Output {@code records: [{id, fields}]}.</li>
 * </ul>
 */
public final class Airtable {

    private static final Pattern BASE = Pattern.compile("app[A-Za-z0-9]{14}");
    private static final Pattern TABLE = Pattern.compile("[A-Za-z0-9 _.-]{1,100}");
    private static final Pattern RECORD = Pattern.compile("rec[A-Za-z0-9]{14}");
    private static final String DEFAULT_BASE = "https://api.airtable.com";

    private Airtable() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("airtable needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String token = Rest.secret(config, "token", "airtable", 16);
            String base = Rest.segment(inputs.get("baseId"), BASE, "baseId");
            String table = Rest.segment(inputs.get("table"), TABLE, "table");
            if (table.equals(".") || table.equals("..")) {
                throw new ServiceHandler.ConnectorException("Invalid table", false);
            }
            Rest.allowed(config.get("allowedTables"), base + "/" + table, "table");
            String url = Rest.base(config, DEFAULT_BASE) + "/v0/" + base + "/" + URLEncoder.encode(table, StandardCharsets.UTF_8).replace("+", "%20");
            Map<String, String> h = Map.of("Authorization", "Bearer " + token);
            String op = config.get("operation") instanceof String o ? o : "";
            switch (op) {
                case "createRecord" -> {
                    Map<String, Object> r = Rest.call(http, url, "POST", h, null, Map.of("fields", fields(inputs)));
                    return Map.of("id", String.valueOf(r.get("id")), "fields", r.get("fields") == null ? Map.of() : r.get("fields"));
                }
                case "updateRecord" -> {
                    String id = Rest.segment(inputs.get("recordId"), RECORD, "recordId");
                    Map<String, Object> r = Rest.call(http, url + "/" + id, "PATCH", h, null, Map.of("fields", fields(inputs)));
                    return Map.of("id", String.valueOf(r.get("id")), "fields", r.get("fields") == null ? Map.of() : r.get("fields"));
                }
                case "listRecords" -> {
                    Map<String, Object> q = new LinkedHashMap<>();
                    q.put("pageSize", Rest.limit(inputs.get("limit"), 20, 100));
                    String f = Rest.text(inputs.get("filterByFormula"), "filterByFormula", 2000, false);
                    if (f != null) {
                        q.put("filterByFormula", f);
                    }
                    Map<String, Object> r = Rest.call(http, url, "GET", h, q, null);
                    List<Map<String, Object>> out = new ArrayList<>();
                    if (r.get("records") instanceof List<?> l) {
                        for (Object o : l) {
                            if (o instanceof Map<?, ?> m) {
                                Map<String, Object> e = new LinkedHashMap<>();
                                e.put("id", m.get("id"));
                                e.put("fields", m.get("fields"));
                                out.add(e);
                            }
                        }
                    }
                    return Map.of("records", out, "count", out.size());
                }
                default -> throw new ServiceHandler.ConnectorException("airtable 'operation' must be createRecord, updateRecord or listRecords", false);
            }
        }

        private static Map<String, Object> fields(Map<String, Object> inputs) throws ServiceHandler.ConnectorException {
            if (!(inputs.get("fields") instanceof Map<?, ?> m) || m.isEmpty() || m.size() > 100) {
                throw new ServiceHandler.ConnectorException("'fields' must be an object with 1..100 entries", false);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), v));
            return out;
        }
    }
}
