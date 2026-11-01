package tech.kayys.wayang.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.mcp.McpTool;

/**
 * A generated MCP server of a tenant. {@code headers} and {@code query} are templates that may hold {@code ${secret:NAME}};
 * they are resolved per call and never returned with values. {@code options} are the generator filters, kept for refresh.
 */
public record McpServerDef(String tenant, String id, String title, String version, String sourceUrl, String baseUrl,
        Map<String, String> headers, Map<String, String> query, Map<String, Object> options, List<McpTool> tools,
        List<String> warnings, List<Map<String, Object>> security, String createdBy, Instant createdAt, Instant updatedAt) {

    public McpServerDef {
        headers = Map.copyOf(headers);
        query = Map.copyOf(query);
        options = Map.copyOf(options);
        tools = List.copyOf(tools);
        warnings = List.copyOf(warnings);
        security = List.copyOf(security);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("tenant", tenant);
        m.put("id", id);
        m.put("title", title);
        m.put("version", version);
        m.put("sourceUrl", sourceUrl);
        m.put("baseUrl", baseUrl);
        m.put("headers", headers);
        m.put("query", query);
        m.put("options", options);
        m.put("tools", tools.stream().map(McpTool::toMap).toList());
        m.put("warnings", warnings);
        m.put("security", security);
        m.put("createdBy", createdBy);
        m.put("createdAt", createdAt.toEpochMilli());
        m.put("updatedAt", updatedAt.toEpochMilli());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static McpServerDef fromMap(Map<String, Object> m) {
        List<McpTool> tools = new ArrayList<>();
        for (Object t : (List<Object>) m.get("tools")) {
            tools.add(McpTool.fromMap((Map<String, Object>) t));
        }
        Map<String, String> headers = new LinkedHashMap<>();
        ((Map<String, Object>) m.get("headers")).forEach((k, v) -> headers.put(k, String.valueOf(v)));
        Map<String, String> query = new LinkedHashMap<>();
        ((Map<String, Object>) m.get("query")).forEach((k, v) -> query.put(k, String.valueOf(v)));
        return new McpServerDef((String) m.get("tenant"), (String) m.get("id"), (String) m.get("title"), (String) m.get("version"),
                (String) m.get("sourceUrl"), (String) m.get("baseUrl"), headers, query, (Map<String, Object>) m.get("options"), tools,
                (List<String>) m.get("warnings"), (List<Map<String, Object>>) m.get("security"), (String) m.get("createdBy"),
                Instant.ofEpochMilli(((Number) m.get("createdAt")).longValue()), Instant.ofEpochMilli(((Number) m.get("updatedAt")).longValue()));
    }
}
