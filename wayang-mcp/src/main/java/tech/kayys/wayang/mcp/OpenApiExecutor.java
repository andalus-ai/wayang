package tech.kayys.wayang.mcp;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.connectors.HttpConnector;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Executes a generated tool: validates the arguments against the tool's schema, builds the HTTP request and returns the
 * response. The host is fixed by {@code baseUrl}; arguments are only ever percent-encoded into path segments or query
 * values, so they cannot redirect the call. Goes through {@link HttpConnector}, so the SSRF guard, size and time limits
 * and "never follow redirects" apply.
 */
public final class OpenApiExecutor {

    private static final int MAX_TEXT = 50_000;
    private static final int MAX_QUERY_VALUES = 200;

    /** Where and how to call: base URL plus fixed credential headers/query values (secrets already resolved). */
    public record Target(String baseUrl, Map<String, String> headers, Map<String, String> query, long timeoutMs, int maxResponseBytes) {
        public Target {
            headers = headers == null ? Map.of() : Map.copyOf(headers);
            query = query == null ? Map.of() : Map.copyOf(query);
        }

        public static Target of(String baseUrl) {
            return new Target(baseUrl, Map.of(), Map.of(), 20_000, 1 << 20);
        }
    }

    private final HttpConnector http;

    public OpenApiExecutor(UrlGuard guard) {
        this.http = new HttpConnector(guard);
    }

    public ToolResult call(McpTool tool, Target target, Map<String, Object> args) {
        if (target.baseUrl() == null || target.baseUrl().isBlank()) {
            return ToolResult.error("This server has no base URL configured");
        }
        Map<String, Object> a = args == null ? Map.of() : args;
        List<String> problems = SchemaCheck.validate(tool.inputSchema(), a);
        if (!problems.isEmpty()) {
            return ToolResult.error("Invalid arguments: " + String.join("; ", problems));
        }
        McpTool.Binding b = tool.binding();
        try {
            String path = b.path();
            List<String> query = new ArrayList<>();
            Map<String, String> headers = new LinkedHashMap<>(target.headers());
            for (McpTool.Param p : b.params()) {
                Object v = a.get(p.arg());
                if (v == null) {
                    continue;
                }
                switch (p.in()) {
                    case "path" -> {
                        String s = scalar(v, p.arg());
                        if (s.isEmpty() || s.equals(".") || s.equals("..")) {
                            return ToolResult.error("Invalid value for path parameter '" + p.arg() + "'");
                        }
                        path = path.replace("{" + p.name() + "}", enc(s));
                    }
                    case "query" -> {
                        if (v instanceof List<?> l) {
                            if (l.size() > MAX_QUERY_VALUES) {
                                return ToolResult.error("Too many values for '" + p.arg() + "'");
                            }
                            for (Object o : l) {
                                query.add(enc(p.name()) + "=" + enc(scalar(o, p.arg())));
                            }
                        } else {
                            query.add(enc(p.name()) + "=" + enc(scalar(v, p.arg())));
                        }
                    }
                    case "header" -> headers.put(p.name(), v instanceof List<?> l
                            ? String.join(",", l.stream().map(o -> scalar(o, p.arg())).toList()) : scalar(v, p.arg()));
                    default -> {
                    }
                }
            }
            target.query().forEach((k, v) -> query.add(enc(k) + "=" + enc(v)));
            String url = trimSlash(target.baseUrl()) + path + (query.isEmpty() ? "" : "?" + String.join("&", query));

            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("url", url);
            cfg.put("method", b.method());
            cfg.put("timeoutMs", target.timeoutMs());
            cfg.put("maxResponseBytes", target.maxResponseBytes());
            Map<String, Object> in = new LinkedHashMap<>();
            if (b.bodyArg() != null && a.get(b.bodyArg()) != null) {
                Object body = a.get(b.bodyArg());
                if ("application/x-www-form-urlencoded".equals(b.bodyContentType())) {
                    headers.put("Content-Type", b.bodyContentType());
                    in.put("body", form(body));
                } else {
                    headers.put("Content-Type", b.bodyContentType() == null ? "application/json" : b.bodyContentType());
                    in.put("body", Json.write(body));
                }
            }
            headers.putIfAbsent("Accept", "application/json");
            cfg.put("headers", headers);
            Map<String, Object> res = http.call(cfg, in);
            return result(res.get("body"));
        } catch (ServiceHandler.ConnectorException e) {
            return ToolResult.error(clip(String.valueOf(e.getMessage()), 1000));
        } catch (IllegalArgumentException e) {
            return ToolResult.error(clip(String.valueOf(e.getMessage()), 300));
        } catch (Exception e) {
            return ToolResult.error("Call failed: " + e.getClass().getSimpleName());
        }
    }

    @SuppressWarnings("unchecked")
    private static ToolResult result(Object body) {
        if (body == null || body instanceof String s && s.isBlank()) {
            return ToolResult.ok("(empty response)", null);
        }
        if (body instanceof Map<?, ?> m) {
            return ToolResult.ok(clip(Json.write(m), MAX_TEXT), (Map<String, Object>) m);
        }
        if (body instanceof List<?> l) {
            return ToolResult.ok(clip(Json.write(l), MAX_TEXT), Map.of("result", l));
        }
        return ToolResult.ok(clip(String.valueOf(body), MAX_TEXT), null);
    }

    private static String form(Object body) {
        if (!(body instanceof Map<?, ?> m)) {
            throw new IllegalArgumentException("A form body must be an object");
        }
        List<String> parts = new ArrayList<>();
        for (var e : m.entrySet()) {
            if (e.getValue() instanceof List<?> l) {
                for (Object o : l) {
                    parts.add(enc(String.valueOf(e.getKey())) + "=" + enc(scalar(o, String.valueOf(e.getKey()))));
                }
            } else if (e.getValue() != null) {
                parts.add(enc(String.valueOf(e.getKey())) + "=" + enc(scalar(e.getValue(), String.valueOf(e.getKey()))));
            }
        }
        return String.join("&", parts);
    }

    private static String scalar(Object v, String what) {
        if (v instanceof String || v instanceof Number || v instanceof Boolean) {
            if (v instanceof Number n && n.doubleValue() == Math.rint(n.doubleValue()) && Math.abs(n.doubleValue()) < 1e15) {
                return Long.toString(n.longValue());
            }
            return String.valueOf(v);
        }
        throw new IllegalArgumentException("'" + what + "' must be a string, number or boolean");
    }

    /** RFC 3986 unreserved characters pass; everything else, including '/', is percent-encoded. */
    static String enc(String s) {
        return URLEncoder.encode(s, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static String trimSlash(String s) {
        String r = s;
        while (r.endsWith("/")) {
            r = r.substring(0, r.length() - 1);
        }
        return r;
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) + "..." : s;
    }

}
