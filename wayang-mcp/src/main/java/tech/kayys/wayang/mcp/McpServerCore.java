package tech.kayys.wayang.mcp;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.json.Json;

/**
 * A stateless MCP server (Streamable HTTP, JSON responses only) over a fixed list of tools: {@code initialize},
 * {@code ping}, {@code tools/list}, {@code tools/call}; notifications are accepted and ignored. Transport and
 * authentication belong to the caller: POST the body to {@link #handle} and send back the returned JSON, or HTTP 202
 * when it returns null (notifications only).
 */
public final class McpServerCore {

    /** Runs one tool; may throw, which becomes an error result. */
    @FunctionalInterface
    public interface Invoker {
        ToolResult invoke(McpTool tool, Map<String, Object> arguments);
    }

    public static final List<String> SUPPORTED = List.of("2025-06-18", "2025-03-26", "2024-11-05");
    static final int MAX_BODY = 1 << 20;
    private static final int MAX_BATCH = 20;

    private final String name;
    private final String version;
    private final String instructions;
    private final Map<String, McpTool> tools = new LinkedHashMap<>();
    private final Invoker invoker;

    public McpServerCore(String name, String version, String instructions, List<McpTool> tools, Invoker invoker) {
        this.name = name;
        this.version = version;
        this.instructions = instructions;
        tools.forEach(t -> this.tools.put(t.name(), t));
        this.invoker = invoker;
    }

    /** @return the JSON response, or null when there is nothing to answer (only notifications) */
    public String handle(String body) {
        if (body == null || body.length() > MAX_BODY) {
            return Json.write(error(null, -32600, "Request too large or empty"));
        }
        Object parsed;
        try {
            parsed = Json.read(body);
        } catch (RuntimeException e) {
            return Json.write(error(null, -32700, "Parse error"));
        }
        if (parsed instanceof List<?> batch) {
            if (batch.isEmpty() || batch.size() > MAX_BATCH) {
                return Json.write(error(null, -32600, "Batch must hold 1.." + MAX_BATCH + " requests"));
            }
            List<Object> out = new ArrayList<>();
            for (Object o : batch) {
                Map<String, Object> r = one(o);
                if (r != null) {
                    out.add(r);
                }
            }
            return out.isEmpty() ? null : Json.write(out);
        }
        Map<String, Object> r = one(parsed);
        return r == null ? null : Json.write(r);
    }

    private Map<String, Object> one(Object o) {
        if (!(o instanceof Map<?, ?> m) || !"2.0".equals(m.get("jsonrpc")) || !(m.get("method") instanceof String method)) {
            // a response object from the client, or garbage
            if (o instanceof Map<?, ?> m2 && (m2.containsKey("result") || m2.containsKey("error"))) {
                return null;
            }
            return error(null, -32600, "Invalid Request");
        }
        Object id = m.get("id");
        boolean notification = !m.containsKey("id");
        if (!notification && !(id instanceof String || id instanceof Number)) {
            return error(null, -32600, "Invalid id");
        }
        Map<String, Object> params = m.get("params") instanceof Map<?, ?> p ? cast(p) : Map.of();
        if (notification) {
            return null;
        }
        try {
            return switch (method) {
                case "initialize" -> ok(id, initialize(params));
                case "ping" -> ok(id, Map.of());
                case "tools/list" -> ok(id, Map.of("tools", tools.values().stream().map(McpTool::toMcp).toList()));
                case "tools/call" -> call(id, params);
                default -> error(id, -32601, "Method not found");
            };
        } catch (RuntimeException e) {
            return error(id, -32603, "Internal error");
        }
    }

    private Map<String, Object> initialize(Map<String, Object> params) {
        String asked = params.get("protocolVersion") instanceof String s ? s : "";
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("protocolVersion", SUPPORTED.contains(asked) ? asked : SUPPORTED.get(0));
        r.put("capabilities", Map.of("tools", Map.of("listChanged", false)));
        r.put("serverInfo", Map.of("name", name, "version", version));
        if (instructions != null && !instructions.isBlank()) {
            r.put("instructions", instructions);
        }
        return r;
    }

    private Map<String, Object> call(Object id, Map<String, Object> params) {
        if (!(params.get("name") instanceof String tn)) {
            return error(id, -32602, "Missing tool name");
        }
        McpTool tool = tools.get(tn);
        if (tool == null) {
            return error(id, -32602, "Unknown tool: " + (tn.length() > 80 ? tn.substring(0, 80) : tn));
        }
        Object args = params.get("arguments");
        if (args != null && !(args instanceof Map)) {
            return error(id, -32602, "arguments must be an object");
        }
        ToolResult tr;
        try {
            tr = invoker.invoke(tool, args == null ? Map.of() : cast((Map<?, ?>) args));
        } catch (RuntimeException e) {
            tr = ToolResult.error("Tool failed: " + e.getClass().getSimpleName());
        }
        Map<String, Object> res = new LinkedHashMap<>();
        res.put("content", List.of(Map.of("type", "text", "text", tr.text() == null ? "" : tr.text())));
        if (tr.structured() != null && !tr.isError()) {
            res.put("structuredContent", tr.structured());
        }
        res.put("isError", tr.isError());
        return ok(id, res);
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> cast(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static Map<String, Object> ok(Object id, Object result) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("result", result);
        return r;
    }

    private static Map<String, Object> error(Object id, int code, String message) {
        Map<String, Object> r = new LinkedHashMap<>();
        r.put("jsonrpc", "2.0");
        r.put("id", id);
        r.put("error", Map.of("code", code, "message", message));
        return r;
    }

}
