package tech.kayys.wayang.mcp;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tech.kayys.wayang.connectors.HttpConnector;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Connector {@code openapi}: give a Swagger 2.0 / OpenAPI 3.x document and an operation, and the node calls it. No server to register first.
 *
 * <p>Config: exactly one of {@code spec} (the document text, JSON or YAML) or {@code specUrl}; {@code operation} (the generated tool name, see
 * {@code listOperations}); {@code baseUrl} (required when {@code headers}/{@code query} carry credentials, otherwise taken from the document);
 * {@code methods} (default {@code GET, HEAD}: anything that changes data must be named here); {@code includeTags}, {@code excludeTags},
 * {@code includePaths}, {@code excludePaths}; fixed {@code headers} and {@code query} (credentials as {@code ${secret:NAME}}); {@code timeoutMs};
 * {@code failOnError} (default true). <b>Input mapper</b>: the node's {@code inputs} are the operation's arguments (path/query/header parameters
 * and {@code body}). Output: {@code result} (the JSON response), {@code text}, {@code isError}.
 *
 * <p>{@code operation: listOperations} returns {@code operations} (name, description, method, path, inputSchema), {@code title} and
 * {@code warnings}, which is what a form needs to offer the operations and their parameters.
 *
 * <p>Safety: the document decides nothing about where credentials go. A document is untrusted input (its server URL can point anywhere), so when
 * credentials are configured the {@code baseUrl} must be given by whoever builds the node. The host is then fixed, arguments are only encoded into
 * path segments and query values, the SSRF guard applies, redirects are not followed.
 */
final class OpenApiConnector implements ServiceHandler.Connector {

    static final int MAX_SPEC_BYTES = 5_000_000;
    private static final int CACHE_SIZE = 16;
    private static final long SPEC_TTL_MS = 60_000;
    private static final Set<String> METHODS = Set.of("GET", "HEAD", "POST", "PUT", "PATCH", "DELETE");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern QUERY_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");

    private final HttpConnector http;
    private final OpenApiExecutor executor;
    private final java.util.function.LongSupplier clock;

    private record Cached<T>(T value, long at) {
    }

    private final Map<String, Cached<String>> specs = lru();
    private final Map<String, OpenApiToMcp.Result> parsed = lru();

    OpenApiConnector(UrlGuard guard) {
        this(guard, System::currentTimeMillis);
    }

    OpenApiConnector(UrlGuard guard, java.util.function.LongSupplier clock) {
        this.http = new HttpConnector(guard);
        this.executor = new OpenApiExecutor(guard);
        this.clock = clock;
    }

    private static <V> Map<String, V> lru() {
        return java.util.Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, true) {
            private static final long serialVersionUID = 1L;

            @Override
            protected boolean removeEldestEntry(Map.Entry<String, V> e) {
                return size() > CACHE_SIZE;
            }
        });
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("openapi needs node configuration (spec or specUrl, operation)", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        String inline = config.get("spec") instanceof String s && !s.isBlank() ? s : null;
        String specUrl = config.get("specUrl") instanceof String u && !u.isBlank() ? u : null;
        if (config.get("spec") != null && inline == null || config.get("specUrl") != null && specUrl == null
                || (inline == null) == (specUrl == null)) {
            throw new ServiceHandler.ConnectorException("openapi needs exactly one of 'spec' (the document) or 'specUrl'", false);
        }
        String operation = config.get("operation") instanceof String o && !o.isBlank() ? o : null;
        if (operation == null) {
            throw new ServiceHandler.ConnectorException("openapi needs 'operation' (a tool name, or listOperations)", false);
        }
        Map<String, String> headers = strings(config.get("headers"), "headers", HEADER);
        Map<String, String> query = strings(config.get("query"), "query", QUERY_KEY);
        String baseUrl = config.get("baseUrl") instanceof String b && !b.isBlank() ? b : null;
        if (baseUrl == null && (!headers.isEmpty() || !query.isEmpty())) {
            throw new ServiceHandler.ConnectorException(
                    "openapi: set 'baseUrl' when 'headers' or 'query' carry credentials (the document must not choose where they are sent)", false);
        }
        OpenApiToMcp.Options options = options(config, baseUrl, specUrl);
        String text = inline != null ? inline : fetch(specUrl);
        if (text.length() > MAX_SPEC_BYTES) {
            throw new ServiceHandler.ConnectorException("openapi: the document is larger than " + MAX_SPEC_BYTES + " characters", false);
        }
        OpenApiToMcp.Result gen = generate(text, options);

        if (operation.equals("listOperations")) {
            List<Object> ops = new ArrayList<>();
            for (McpTool t : gen.tools()) {
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", t.name());
                m.put("description", t.description());
                m.put("method", t.binding().method());
                m.put("path", t.binding().path());
                m.put("inputSchema", t.inputSchema());
                ops.add(m);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("title", gen.title());
            out.put("baseUrl", gen.baseUrl());
            out.put("operations", ops);
            out.put("warnings", gen.warnings());
            return out;
        }
        McpTool tool = gen.tools().stream().filter(t -> t.name().equals(operation)).findFirst().orElse(null);
        if (tool == null) {
            List<String> names = gen.tools().stream().map(McpTool::name).limit(20).toList();
            throw new ServiceHandler.ConnectorException("openapi: no operation '" + clip(operation) + "' (with the chosen methods and filters). Available: " + names
                    + (gen.tools().size() > 20 ? " ..." : ""), false);
        }
        String base = baseUrl != null ? baseUrl : gen.baseUrl();
        if (base == null || base.isBlank()) {
            throw new ServiceHandler.ConnectorException("openapi: the document gives no absolute server URL: set 'baseUrl'", false);
        }
        long timeout = config.get("timeoutMs") instanceof Number n ? Math.max(1_000, Math.min(120_000, n.longValue())) : 20_000;
        ToolResult r = executor.call(tool, new OpenApiExecutor.Target(base, headers, query, timeout, 1 << 20), inputs);
        boolean fail = !Boolean.FALSE.equals(config.get("failOnError"));
        if (r.isError() && fail) {
            throw new ServiceHandler.ConnectorException("openapi " + operation + ": " + clip(r.text()), false);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("result", r.structured() instanceof Map<?, ?> m && m.size() == 1 && m.containsKey("result") ? m.get("result") : r.structured());
        out.put("text", r.text());
        out.put("isError", r.isError());
        return out;
    }

    private OpenApiToMcp.Result generate(String text, OpenApiToMcp.Options options) throws ServiceHandler.ConnectorException {
        String key = sha(text) + "|" + options;
        OpenApiToMcp.Result hit = parsed.get(key);
        if (hit != null) {
            return hit;
        }
        try {
            OpenApiToMcp.Result r = OpenApiToMcp.generate(text, options);
            parsed.put(key, r);
            return r;
        } catch (OpenApiToMcp.SpecException e) {
            throw new ServiceHandler.ConnectorException("openapi: " + clip(e.getMessage()), false);
        }
    }

    private String fetch(String url) throws Exception {
        long now = clock.getAsLong();
        Cached<String> c = specs.get(url);
        if (c != null && now - c.at() < SPEC_TTL_MS) {
            return c.value();
        }
        Map<String, Object> res = http.call(Map.of("url", url, "method", "GET", "timeoutMs", 20_000, "maxResponseBytes", MAX_SPEC_BYTES,
                "headers", Map.of("Accept", "application/json, application/yaml, text/yaml, */*")), Map.of());
        Object body = res.get("body");
        if (body == null || body instanceof String s && s.isBlank()) {
            throw new ServiceHandler.ConnectorException("openapi: the document URL returned nothing", false);
        }
        String text = body instanceof String s ? s : Json.write(body); // JSON content types arrive parsed
        specs.put(url, new Cached<>(text, now));
        return text;
    }

    @SuppressWarnings("unchecked")
    private static OpenApiToMcp.Options options(Map<String, Object> c, String baseUrl, String specUrl) throws ServiceHandler.ConnectorException {
        Set<String> methods = null;
        if (c.get("methods") != null) {
            if (!(c.get("methods") instanceof List<?> l) || l.isEmpty() || l.size() > 6) {
                throw new ServiceHandler.ConnectorException("openapi 'methods' is a list of GET, HEAD, POST, PUT, PATCH, DELETE", false);
            }
            methods = new java.util.TreeSet<>();
            for (Object o : l) {
                String m = String.valueOf(o).toUpperCase(java.util.Locale.ROOT);
                if (!METHODS.contains(m)) {
                    throw new ServiceHandler.ConnectorException("openapi 'methods' has an unknown method '" + clip(String.valueOf(o)) + "'", false);
                }
                methods.add(m);
            }
        }
        return new OpenApiToMcp.Options(baseUrl, specUrl, methods, set(c.get("includeTags")), set(c.get("excludeTags")), list(c.get("includePaths")),
                list(c.get("excludePaths")), false, 500, null);
    }

    private static Set<String> set(Object o) throws ServiceHandler.ConnectorException {
        return o == null ? null : Set.copyOf(list(o));
    }

    private static List<String> list(Object o) throws ServiceHandler.ConnectorException {
        if (o == null) {
            return List.of();
        }
        if (!(o instanceof List<?> l) || l.size() > 100) {
            throw new ServiceHandler.ConnectorException("openapi: filters are lists of at most 100 strings", false);
        }
        List<String> out = new ArrayList<>();
        for (Object x : l) {
            if (!(x instanceof String s) || s.isBlank() || s.length() > 200) {
                throw new ServiceHandler.ConnectorException("openapi: filters are lists of at most 100 strings", false);
            }
            out.add(s);
        }
        return out;
    }

    private static Map<String, String> strings(Object o, String what, Pattern key) throws ServiceHandler.ConnectorException {
        if (o == null) {
            return Map.of();
        }
        if (!(o instanceof Map<?, ?> m) || m.size() > 20) {
            throw new ServiceHandler.ConnectorException("openapi '" + what + "' is a map of at most 20 strings", false);
        }
        Map<String, String> out = new LinkedHashMap<>();
        for (Map.Entry<?, ?> e : m.entrySet()) {
            if (!(e.getKey() instanceof String k) || !key.matcher(k).matches() || !(e.getValue() instanceof String v) || v.length() > 4096
                    || v.indexOf('\r') >= 0 || v.indexOf('\n') >= 0) {
                throw new ServiceHandler.ConnectorException("openapi '" + what + "' has an invalid entry", false);
            }
            out.put(k, v);
        }
        return out;
    }

    private static String sha(String s) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static String clip(String s) {
        String t = s == null ? "" : s.replaceAll("\\p{Cntrl}", " ");
        return t.length() > 300 ? t.substring(0, 300) : t;
    }
}
