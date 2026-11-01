package tech.kayys.wayang.api;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.kayys.wayang.mcp.McpServerCore;
import tech.kayys.wayang.mcp.McpTool;
import tech.kayys.wayang.mcp.OpenApiExecutor;
import tech.kayys.wayang.mcp.OpenApiToMcp;
import tech.kayys.wayang.mcp.ToolResult;
import tech.kayys.wayang.runtime.SecretResolver;

/**
 * Tenant-scoped MCP servers generated from OpenAPI/Swagger. Management needs DEFINITION_WRITE (read: DEFINITION_READ);
 * the MCP endpoint needs DEFINITION_READ for {@code tools/list} and RUN_START to run a tool, because a tool can have
 * side effects even though only read-only operations are exposed by default.
 *
 * <p>Credentials for the target API live in {@code headers}/{@code query} templates and must be {@code ${secret:NAME}}
 * references; they are resolved per call for the caller's tenant, and any resolved value is masked in the tool output.
 */
public final class McpService {

    /** Port: fetch a spec by URL (guarded, size-limited). */
    @FunctionalInterface
    public interface SpecFetcher {
        String fetch(String url) throws Exception;
    }

    /** Generator input. Exactly one of {@code spec} / {@code specUrl}. */
    public record Request(String spec, String specUrl, String baseUrl, Map<String, String> headers, Map<String, String> query,
            Set<String> methods, Set<String> includeTags, Set<String> excludeTags, List<String> includePaths,
            List<String> excludePaths, Integer maxTools, String namePrefix) {
    }

    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,60}");
    private static final Pattern HEADER = Pattern.compile("[A-Za-z0-9-]{1,64}");
    private static final Pattern QUERY_KEY = Pattern.compile("[A-Za-z0-9_.-]{1,64}");
    private static final Pattern SENSITIVE = Pattern.compile("(?i).*(authorization|token|secret|password|key|cookie|auth).*");
    private static final Pattern SECRET_REF = Pattern.compile("\\$\\{secret:([A-Za-z0-9_.-]{1,100})}");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length", "connection", "transfer-encoding", "upgrade",
            "expect", "content-type", "accept");
    private static final int MAX_SERVERS = 100;

    private final McpStore store;
    private final SpecFetcher fetcher;
    private final OpenApiExecutor executor;
    private final SecretResolver secrets;
    private final Clock clock;

    public McpService(McpStore store, SpecFetcher fetcher, OpenApiExecutor executor, SecretResolver secrets, Clock clock) {
        this.store = store;
        this.fetcher = fetcher;
        this.executor = executor;
        this.secrets = secrets;
        this.clock = clock;
    }

    // ------------------------------------------------------------------ management

    /** Generates without storing. */
    public OpenApiToMcp.Result preview(Principal p, Request r) {
        need(p, Permission.DEFINITION_WRITE);
        return generate(r, specText(r));
    }

    public McpServerDef create(Principal p, String id, Request r) {
        need(p, Permission.DEFINITION_WRITE);
        if (id == null || !ID.matcher(id).matches()) {
            throw ApiException.badRequest("Server id must match " + ID.pattern());
        }
        if (store.list(p.tenantId()).size() >= MAX_SERVERS) {
            throw ApiException.badRequest("At most " + MAX_SERVERS + " MCP servers per tenant");
        }
        Map<String, String> headers = checkHeaders(r.headers());
        Map<String, String> query = checkQuery(r.query());
        OpenApiToMcp.Result g = generate(r, specText(r));
        if (g.baseUrl().isBlank()) {
            throw ApiException.badRequest("No base URL: set baseUrl (the spec does not give an absolute server URL)");
        }
        if (g.tools().isEmpty()) {
            throw new ApiException(ApiException.Code.VALIDATION_FAILED, "No tools were generated", g.warnings());
        }
        var now = clock.instant();
        McpServerDef d = new McpServerDef(p.tenantId(), id, g.title(), g.version(), r.specUrl(), g.baseUrl(), headers, query,
                options(r), g.tools(), g.warnings(), g.security(), p.userId(), now, now);
        store.create(d);
        return d;
    }

    /** Re-fetches the spec URL and regenerates; the inline-spec servers cannot be refreshed. */
    public McpServerDef refresh(Principal p, String id) {
        need(p, Permission.DEFINITION_WRITE);
        McpServerDef d = get0(p, id);
        if (d.sourceUrl() == null || d.sourceUrl().isBlank()) {
            throw ApiException.badRequest("This server was created from an inline spec; create it again to change it");
        }
        Request r = fromOptions(d);
        OpenApiToMcp.Result g = generate(r, specText(r));
        if (g.tools().isEmpty()) {
            throw new ApiException(ApiException.Code.VALIDATION_FAILED, "The refreshed spec generates no tools; keeping the old ones", g.warnings());
        }
        McpServerDef n = new McpServerDef(d.tenant(), d.id(), g.title(), g.version(), d.sourceUrl(), g.baseUrl().isBlank() ? d.baseUrl() : g.baseUrl(),
                d.headers(), d.query(), d.options(), g.tools(), g.warnings(), g.security(), d.createdBy(), d.createdAt(), clock.instant());
        store.update(n);
        return n;
    }

    public List<McpServerDef> list(Principal p) {
        need(p, Permission.DEFINITION_READ);
        return store.list(p.tenantId());
    }

    public McpServerDef get(Principal p, String id) {
        need(p, Permission.DEFINITION_READ);
        return get0(p, id);
    }

    public void delete(Principal p, String id) {
        need(p, Permission.DEFINITION_WRITE);
        if (!store.delete(p.tenantId(), id)) {
            throw ApiException.notFound("MCP server " + id);
        }
    }

    // ------------------------------------------------------------------ serving

    /** @return the JSON-RPC response body, or null for HTTP 202 (notifications) */
    public String rpc(Principal p, String id, String body) {
        need(p, Permission.DEFINITION_READ);
        McpServerDef d = get0(p, id);
        McpServerCore core = new McpServerCore("wayang-" + d.id(), d.version() == null || d.version().isBlank() ? "1" : d.version(),
                d.title() == null || d.title().isBlank() ? null : "Tools generated from " + d.title(), d.tools(), (tool, args) -> run(p, d, tool, args));
        return core.handle(body);
    }

    private ToolResult run(Principal p, McpServerDef d, McpTool tool, Map<String, Object> args) {
        if (!p.can(Permission.RUN_START)) {
            return ToolResult.error("Not permitted to run tools (needs " + Permission.RUN_START + ")");
        }
        Set<String> used = new LinkedHashSet<>();
        Map<String, String> headers = new LinkedHashMap<>();
        Map<String, String> query = new LinkedHashMap<>();
        for (var e : d.headers().entrySet()) {
            String v = resolve(d.tenant(), e.getValue(), used);
            if (v == null) {
                return ToolResult.error("A credential for this server is not set");
            }
            headers.put(e.getKey(), v);
        }
        for (var e : d.query().entrySet()) {
            String v = resolve(d.tenant(), e.getValue(), used);
            if (v == null) {
                return ToolResult.error("A credential for this server is not set");
            }
            query.put(e.getKey(), v);
        }
        ToolResult r = executor.call(tool, new OpenApiExecutor.Target(d.baseUrl(), headers, query, 20_000, 1 << 20), args);
        return used.isEmpty() ? r : new ToolResult(r.isError(), mask(r.text(), used), r.structured() == null ? null : maskTree(r.structured(), used));
    }

    // ------------------------------------------------------------------ helpers

    private McpServerDef get0(Principal p, String id) {
        return store.get(p.tenantId(), id).orElseThrow(() -> ApiException.notFound("MCP server " + id));
    }

    private String specText(Request r) {
        boolean inline = r.spec() != null && !r.spec().isBlank();
        boolean url = r.specUrl() != null && !r.specUrl().isBlank();
        if (inline == url) {
            throw ApiException.badRequest("Give exactly one of spec (the document text) or specUrl");
        }
        if (inline) {
            return r.spec();
        }
        String u = r.specUrl().trim();
        if (!(u.startsWith("https://") || u.startsWith("http://"))) {
            throw ApiException.badRequest("specUrl must be http(s)");
        }
        try {
            return fetcher.fetch(u);
        } catch (Exception e) {
            throw new ApiException(ApiException.Code.UNAVAILABLE, "Could not fetch the spec: " + firstLine(e.getMessage()));
        }
    }

    private static OpenApiToMcp.Result generate(Request r, String text) {
        try {
            return OpenApiToMcp.generate(text, new OpenApiToMcp.Options(blank(r.baseUrl()), blank(r.specUrl()), r.methods(), r.includeTags(),
                    r.excludeTags(), r.includePaths(), r.excludePaths(), false, r.maxTools() == null ? 0 : r.maxTools(), r.namePrefix()));
        } catch (OpenApiToMcp.SpecException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    private static String blank(String s) {
        return s == null || s.isBlank() ? null : s.trim();
    }

    private static Map<String, Object> options(Request r) {
        Map<String, Object> o = new LinkedHashMap<>();
        if (blank(r.baseUrl()) != null) {
            o.put("baseUrl", r.baseUrl().trim());
        }
        if (r.methods() != null && !r.methods().isEmpty()) {
            o.put("methods", new ArrayList<>(r.methods()));
        }
        if (r.includeTags() != null && !r.includeTags().isEmpty()) {
            o.put("includeTags", new ArrayList<>(r.includeTags()));
        }
        if (r.excludeTags() != null && !r.excludeTags().isEmpty()) {
            o.put("excludeTags", new ArrayList<>(r.excludeTags()));
        }
        if (r.includePaths() != null && !r.includePaths().isEmpty()) {
            o.put("includePaths", new ArrayList<>(r.includePaths()));
        }
        if (r.excludePaths() != null && !r.excludePaths().isEmpty()) {
            o.put("excludePaths", new ArrayList<>(r.excludePaths()));
        }
        if (r.maxTools() != null) {
            o.put("maxTools", r.maxTools());
        }
        if (blank(r.namePrefix()) != null) {
            o.put("namePrefix", r.namePrefix());
        }
        return o;
    }

    @SuppressWarnings("unchecked")
    private static Request fromOptions(McpServerDef d) {
        Map<String, Object> o = d.options();
        return new Request(null, d.sourceUrl(), (String) o.get("baseUrl"), d.headers(), d.query(), set(o.get("methods")), set(o.get("includeTags")),
                set(o.get("excludeTags")), (List<String>) o.getOrDefault("includePaths", List.of()), (List<String>) o.getOrDefault("excludePaths", List.of()),
                o.get("maxTools") instanceof Number n ? n.intValue() : null, (String) o.get("namePrefix"));
    }

    @SuppressWarnings("unchecked")
    private static Set<String> set(Object o) {
        return o instanceof List<?> l ? new LinkedHashSet<>((List<String>) l) : Set.of();
    }

    private static Map<String, String> checkHeaders(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        for (var e : in.entrySet()) {
            String k = e.getKey();
            String v = e.getValue() == null ? "" : e.getValue();
            if (!HEADER.matcher(k).matches() || FORBIDDEN_HEADERS.contains(k.toLowerCase(Locale.ROOT))) {
                throw ApiException.badRequest("Header name not allowed: " + clip(k));
            }
            credentialRule("Header " + k, k, v);
            out.put(k, v);
        }
        return out;
    }

    private static Map<String, String> checkQuery(Map<String, String> in) {
        Map<String, String> out = new LinkedHashMap<>();
        if (in == null) {
            return out;
        }
        for (var e : in.entrySet()) {
            if (!QUERY_KEY.matcher(e.getKey()).matches()) {
                throw ApiException.badRequest("Query name not allowed: " + clip(e.getKey()));
            }
            credentialRule("Query parameter " + e.getKey(), e.getKey(), e.getValue() == null ? "" : e.getValue());
            out.put(e.getKey(), e.getValue() == null ? "" : e.getValue());
        }
        return out;
    }

    private static void credentialRule(String what, String key, String value) {
        if (value.indexOf('\r') >= 0 || value.indexOf('\n') >= 0 || value.length() > 2000) {
            throw ApiException.badRequest(what + " has an invalid value");
        }
        if (SENSITIVE.matcher(key).matches() && !SECRET_REF.matcher(value).find()) {
            throw ApiException.badRequest(what + " looks like a credential: use a ${secret:NAME} reference, not a literal value");
        }
    }

    private String resolve(String tenant, String template, Set<String> used) {
        Matcher m = SECRET_REF.matcher(template);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String v = secrets == null ? null : secrets.resolve(tenant, m.group(1));
            if (v == null) {
                return null;
            }
            used.add(v);
            m.appendReplacement(sb, Matcher.quoteReplacement(v));
        }
        m.appendTail(sb);
        return sb.toString();
    }

    private static String mask(String s, Set<String> values) {
        String r = s;
        for (String v : values) {
            if (v.length() >= 4) {
                r = r.replace(v, "***");
            }
        }
        return r;
    }

    @SuppressWarnings("unchecked")
    private static Object maskTree(Object o, Set<String> values) {
        if (o instanceof String s) {
            return mask(s, values);
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            ((Map<String, Object>) m).forEach((k, v) -> out.put(k, maskTree(v, values)));
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>();
            l.forEach(v -> out.add(maskTree(v, values)));
            return out;
        }
        return o;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 200 ? l.substring(0, 200) : l;
    }

    private static String clip(String s) {
        return s.length() > 40 ? s.substring(0, 40) : s;
    }

    private static void need(Principal p, Permission perm) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(perm)) {
            throw ApiException.forbidden(perm);
        }
    }
}
