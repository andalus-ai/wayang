package tech.kayys.wayang.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * MCP (Model Context Protocol) client over Streamable HTTP, as connector {@code mcp}.
 *
 * <p>Node config: {@code url} (required), {@code headers} (e.g. {@code Authorization: Bearer ${secret:TOKEN}}),
 * {@code operation} ({@code callTool} default | {@code listTools}), {@code tool} (callTool), {@code failOnToolError}
 * (default true: an {@code isError} result fails the node; false returns it), {@code outputMap}, {@code timeoutMs},
 * {@code maxResponseBytes}. <b>Input mapper</b>: the node's {@code inputs} (name to expression) are the tool arguments.
 * <b>Output mapper</b>: {@code outputMap} (variable to path, e.g. {@code {"city":"result.address.city"}}) is read from
 * {@code {result, text, isError, content}}; those four are always returned as well. {@code result} is the tool's
 * {@code structuredContent}, else its single text block parsed as JSON when it is JSON, else the text.
 *
 * <p>Each call runs initialize, notifications/initialized, the operation and, when the server issued a session, a
 * best-effort session DELETE. Redirects are not followed and the URL guard applies, like every other connector.
 * Responses may be plain JSON or a short {@code text/event-stream}; long-lived streams are not supported.
 */
public final class Mcp {

    static final String DEFAULT_PROTOCOL = "2025-06-18";
    private static final Pattern VAR = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    private Mcp() {
    }

    public static final class Client implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Client(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("mcp needs node configuration (url, tool)", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            if (!(config.get("url") instanceof String url) || url.isBlank()) {
                throw new ServiceHandler.ConnectorException("mcp needs 'url'", false);
            }
            String op = String.valueOf(config.getOrDefault("operation", "callTool"));
            if (!op.equals("callTool") && !op.equals("listTools")) {
                throw new ServiceHandler.ConnectorException("Unsupported mcp operation '" + op + "'", false);
            }
            String tool = config.get("tool") instanceof String t && !t.isBlank() ? t : null;
            if (op.equals("callTool") && tool == null) {
                throw new ServiceHandler.ConnectorException("mcp callTool needs 'tool'", false);
            }
            Map<String, String> userHeaders = new LinkedHashMap<>();
            if (config.get("headers") instanceof Map<?, ?> h) {
                h.forEach((k, v) -> userHeaders.put(String.valueOf(k), String.valueOf(v)));
            }
            Map<String, Object> outMap = config.get("outputMap") instanceof Map<?, ?> m ? castMap(m) : Map.of();
            for (String var : outMap.keySet()) {
                if (!VAR.matcher(var).matches()) {
                    throw new ServiceHandler.ConnectorException("outputMap name '" + var + "' is not a valid variable name", false);
                }
            }
            Session s = new Session(url, userHeaders, config);

            Map<String, Object> init = Map.of("protocolVersion", DEFAULT_PROTOCOL, "capabilities", Map.of(),
                    "clientInfo", Map.of("name", "wayang", "version", "0.0.1"));
            Object initResult = s.request("initialize", init);
            if (initResult instanceof Map<?, ?> im && im.get("protocolVersion") instanceof String pv) {
                s.protocol = pv;
            }
            s.notifyServer("notifications/initialized");
            try {
                if (op.equals("listTools")) {
                    List<Object> tools = new ArrayList<>();
                    String cursor = null;
                    for (int page = 0; page < 20; page++) {
                        Object r = s.request("tools/list", cursor == null ? Map.of() : Map.of("cursor", cursor));
                        if (r instanceof Map<?, ?> rm) {
                            if (rm.get("tools") instanceof List<?> l) {
                                tools.addAll(l);
                            }
                            cursor = rm.get("nextCursor") instanceof String c && !c.isBlank() ? c : null;
                        }
                        if (cursor == null) {
                            break;
                        }
                    }
                    Map<String, Object> out = new LinkedHashMap<>();
                    out.put("tools", tools);
                    return out;
                }
                Map<String, Object> params = new LinkedHashMap<>();
                params.put("name", tool);
                params.put("arguments", inputs == null ? Map.of() : inputs);
                Object r = s.request("tools/call", params);
                return toOutput(r, !Boolean.FALSE.equals(config.get("failOnToolError")), outMap);
            } finally {
                s.close();
            }
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> castMap(Map<?, ?> m) {
            return (Map<String, Object>) m;
        }

        private Map<String, Object> toOutput(Object r, boolean failOnError, Map<String, Object> outMap) throws Exception {
            if (!(r instanceof Map<?, ?> rm)) {
                throw new ServiceHandler.ConnectorException("MCP server returned no result", false);
            }
            List<Object> content = rm.get("content") instanceof List<?> l ? new ArrayList<>(l) : new ArrayList<>();
            StringBuilder text = new StringBuilder();
            int textBlocks = 0;
            for (Object c : content) {
                if (c instanceof Map<?, ?> cm && "text".equals(cm.get("type")) && cm.get("text") instanceof String t) {
                    if (text.length() > 0) {
                        text.append('\n');
                    }
                    text.append(t);
                    textBlocks++;
                }
            }
            boolean isError = Boolean.TRUE.equals(rm.get("isError"));
            if (isError && failOnError) {
                String t = text.toString();
                throw new ServiceHandler.ConnectorException("MCP tool reported an error" + (t.isBlank() ? "" : ": " + (t.length() > 300 ? t.substring(0, 300) : t)), false);
            }
            Object result;
            if (rm.get("structuredContent") != null) {
                result = rm.get("structuredContent");
            } else if (textBlocks == 1 && (text.toString().trim().startsWith("{") || text.toString().trim().startsWith("["))) {
                try {
                    result = Json.read(text.toString());
                } catch (RuntimeException e) {
                    result = text.toString();
                }
            } else {
                result = textBlocks > 0 ? text.toString() : content;
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("result", result);
            out.put("text", text.toString());
            out.put("isError", isError);
            out.put("content", content);
            for (var e : outMap.entrySet()) {
                out.put(e.getKey(), JsonPath.get(out, String.valueOf(e.getValue())));
            }
            return out;
        }

        /** One initialize..close exchange. */
        private final class Session {
            private final String url;
            private final Map<String, String> userHeaders;
            private final Map<String, Object> config;
            private String sessionId;
            private String protocol;
            private long nextId = 1;

            Session(String url, Map<String, String> userHeaders, Map<String, Object> config) {
                this.url = url;
                this.userHeaders = userHeaders;
                this.config = config;
            }

            private Map<String, Object> cfg(String method) {
                Map<String, Object> c = new LinkedHashMap<>();
                c.put("url", url);
                c.put("method", method);
                c.put("timeoutMs", config.getOrDefault("timeoutMs", 30_000));
                if (config.get("maxResponseBytes") != null) {
                    c.put("maxResponseBytes", config.get("maxResponseBytes"));
                }
                Map<String, String> h = new LinkedHashMap<>(userHeaders);
                h.put("Accept", "application/json, text/event-stream");
                if (protocol != null) {
                    h.put("MCP-Protocol-Version", protocol);
                }
                if (sessionId != null) {
                    h.put("Mcp-Session-Id", sessionId);
                }
                c.put("headers", h);
                return c;
            }

            Object request(String method, Map<String, Object> params) throws Exception {
                long id = nextId++;
                Map<String, Object> body = new LinkedHashMap<>();
                body.put("jsonrpc", "2.0");
                body.put("id", id);
                body.put("method", method);
                body.put("params", params);
                Map<String, Object> res = http.call(cfg("POST"), Map.of("body", body));
                if (method.equals("initialize") && res.get("headers") instanceof Map<?, ?> h && h.get("mcp-session-id") instanceof String sid
                        && !sid.isBlank() && sid.length() <= 256 && sid.chars().allMatch(c -> c >= 0x21 && c <= 0x7e)) {
                    sessionId = sid;
                }
                Map<String, Object> msg = pick(res.get("body"), id);
                if (msg == null) {
                    throw new ServiceHandler.ConnectorException("No JSON-RPC response for " + method, true);
                }
                if (msg.get("error") instanceof Map<?, ?> err) {
                    String m = err.get("message") instanceof String ms ? ms : "error";
                    throw new ServiceHandler.ConnectorException("MCP " + method + " failed (" + err.get("code") + "): "
                            + (m.length() > 300 ? m.substring(0, 300) : m), false);
                }
                return msg.get("result");
            }

            void notifyServer(String method) throws Exception {
                http.call(cfg("POST"), Map.of("body", Map.of("jsonrpc", "2.0", "method", method)));
            }

            void close() {
                if (sessionId == null) {
                    return;
                }
                try {
                    http.call(cfg("DELETE"), Map.of());
                } catch (Exception ignored) {
                    // many servers answer 405 (stateless) or have already expired the session
                }
            }
        }

        /** The response with the given id from a JSON body or from a server-sent-events body. */
        @SuppressWarnings("unchecked")
        static Map<String, Object> pick(Object body, long id) {
            if (body instanceof Map<?, ?> m) {
                return idMatches(m, id) ? (Map<String, Object>) m : null;
            }
            if (body instanceof List<?> l) {
                for (Object o : l) {
                    if (o instanceof Map<?, ?> m && idMatches(m, id)) {
                        return (Map<String, Object>) m;
                    }
                }
                return null;
            }
            if (!(body instanceof String s) || s.isBlank()) {
                return null;
            }
            StringBuilder data = new StringBuilder();
            for (String line : (s + "\n\n").split("\r?\n", -1)) {
                if (line.startsWith("data:")) {
                    String v = line.substring(5);
                    data.append(v.startsWith(" ") ? v.substring(1) : v).append('\n');
                } else if (line.isEmpty() && data.length() > 0) {
                    try {
                        Object o = Json.read(data.toString().trim());
                        Map<String, Object> r = pick(o, id);
                        if (r != null) {
                            return r;
                        }
                    } catch (RuntimeException ignored) {
                        // not JSON (keep-alive or comment): skip this event
                    }
                    data.setLength(0);
                }
            }
            return null;
        }

        private static boolean idMatches(Map<?, ?> m, long id) {
            return m.get("id") instanceof Number n && n.longValue() == id && (m.containsKey("result") || m.containsKey("error"));
        }
    }
}
