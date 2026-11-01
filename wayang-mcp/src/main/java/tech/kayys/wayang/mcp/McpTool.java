package tech.kayys.wayang.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** One generated MCP tool and how a call to it maps onto an HTTP request. */
public record McpTool(String name, String description, Map<String, Object> inputSchema, Map<String, Object> outputSchema,
        Hints hints, Binding binding) {

    /** MCP tool annotations. */
    public record Hints(boolean readOnly, boolean destructive, boolean idempotent) {
    }

    /** One tool argument that travels as a path, query or header parameter. */
    public record Param(String arg, String name, String in, boolean required) {
    }

    /**
     * @param bodyArg         name of the argument holding the request body, or null
     * @param bodyContentType {@code application/json} (or a +json type) or {@code application/x-www-form-urlencoded}
     */
    public record Binding(String method, String path, List<Param> params, String bodyArg, String bodyContentType) {
        public Binding {
            params = List.copyOf(params);
        }
    }

    /** The MCP {@code tools/list} entry. */
    public Map<String, Object> toMcp() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("inputSchema", inputSchema);
        if (outputSchema != null) {
            m.put("outputSchema", outputSchema);
        }
        Map<String, Object> a = new LinkedHashMap<>();
        a.put("readOnlyHint", hints.readOnly());
        a.put("destructiveHint", hints.destructive());
        a.put("idempotentHint", hints.idempotent());
        a.put("openWorldHint", true);
        m.put("annotations", a);
        return m;
    }

    /** Persistable form (JSON-compatible). */
    public Map<String, Object> toMap() {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("name", name);
        m.put("description", description);
        m.put("inputSchema", inputSchema);
        m.put("outputSchema", outputSchema);
        m.put("readOnly", hints.readOnly());
        m.put("destructive", hints.destructive());
        m.put("idempotent", hints.idempotent());
        m.put("method", binding.method());
        m.put("path", binding.path());
        List<Object> ps = new ArrayList<>();
        for (Param p : binding.params()) {
            ps.add(Map.of("arg", p.arg(), "name", p.name(), "in", p.in(), "required", p.required()));
        }
        m.put("params", ps);
        m.put("bodyArg", binding.bodyArg());
        m.put("bodyContentType", binding.bodyContentType());
        return m;
    }

    @SuppressWarnings("unchecked")
    public static McpTool fromMap(Map<String, Object> m) {
        List<Param> ps = new ArrayList<>();
        for (Object o : (List<Object>) m.get("params")) {
            Map<String, Object> p = (Map<String, Object>) o;
            ps.add(new Param((String) p.get("arg"), (String) p.get("name"), (String) p.get("in"), Boolean.TRUE.equals(p.get("required"))));
        }
        return new McpTool((String) m.get("name"), (String) m.get("description"), (Map<String, Object>) m.get("inputSchema"),
                (Map<String, Object>) m.get("outputSchema"),
                new Hints(Boolean.TRUE.equals(m.get("readOnly")), Boolean.TRUE.equals(m.get("destructive")), Boolean.TRUE.equals(m.get("idempotent"))),
                new Binding((String) m.get("method"), (String) m.get("path"), ps, (String) m.get("bodyArg"), (String) m.get("bodyContentType")));
    }
}
