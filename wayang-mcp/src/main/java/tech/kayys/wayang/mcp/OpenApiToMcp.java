package tech.kayys.wayang.mcp;

import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Date;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.yaml.snakeyaml.LoaderOptions;
import org.yaml.snakeyaml.Yaml;
import org.yaml.snakeyaml.constructor.SafeConstructor;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Turns a Swagger 2.0 or OpenAPI 3.x document (JSON or YAML text) into MCP tools, one per operation.
 *
 * <p>Input: each path, query and header parameter becomes a top-level argument; the request body becomes one argument
 * ({@code body}). Schemas are converted to plain JSON Schema: local {@code $ref}s are inlined (recursive ones are cut
 * off), {@code allOf} object parts are merged, {@code nullable} becomes a type union, read-only properties are dropped
 * from requests. Output: the first 2xx JSON response schema when it is an object.
 *
 * <p>Safe by default: only {@code GET}/{@code HEAD} operations are exposed, deprecated ones are skipped, at most
 * {@code maxTools} are produced, external {@code $ref}s are not followed. Everything skipped is listed in
 * {@link Result#warnings()}. Descriptions come from the spec and so are untrusted text given to a model: they are cut
 * to 1000 characters and stripped of control characters, but a malicious spec can still try to steer an agent, so only
 * import specs you trust.
 */
public final class OpenApiToMcp {

    private static final int MAX_SPEC_CHARS = 5_000_000;
    private static final int MAX_SCHEMA_DEPTH = 10;
    private static final int MAX_SCHEMA_JSON = 100_000;
    /** A list, not a Set: iteration order decides the order of generated tools and must be stable. */
    private static final List<String> HTTP_METHODS = List.of("get", "put", "post", "delete", "patch", "head");
    private static final Set<String> SKIPPED_HEADERS = Set.of("authorization", "content-type", "accept", "content-length", "host",
            "cookie");
    private static final Pattern PATH_VAR = Pattern.compile("\\{([^{}/]+)}");
    private static final Pattern NAME_OK = Pattern.compile("[A-Za-z0-9_-]+");
    private static final Set<String> COPY_KEYS = Set.of("type", "format", "enum", "const", "default", "description", "title", "minimum",
            "maximum", "minLength", "maxLength", "pattern", "minItems", "maxItems", "uniqueItems", "minProperties", "maxProperties",
            "multipleOf");

    private OpenApiToMcp() {
    }

    /** Which operations to expose and how. */
    public record Options(String baseUrl, String sourceUrl, Set<String> methods, Set<String> includeTags, Set<String> excludeTags,
            List<String> includePaths, List<String> excludePaths, boolean includeDeprecated, int maxTools, String namePrefix) {

        public Options {
            methods = methods == null || methods.isEmpty() ? Set.of("GET", "HEAD") : upper(methods);
            includeTags = includeTags == null ? Set.of() : Set.copyOf(includeTags);
            excludeTags = excludeTags == null ? Set.of() : Set.copyOf(excludeTags);
            includePaths = includePaths == null ? List.of() : List.copyOf(includePaths);
            excludePaths = excludePaths == null ? List.of() : List.copyOf(excludePaths);
            maxTools = maxTools <= 0 ? 100 : Math.min(maxTools, 500);
            namePrefix = namePrefix == null ? "" : namePrefix;
        }

        public static Options defaults() {
            return new Options(null, null, null, null, null, null, null, false, 0, null);
        }

        private static Set<String> upper(Set<String> s) {
            Set<String> o = new LinkedHashSet<>();
            s.forEach(x -> o.add(x.toUpperCase(Locale.ROOT)));
            return Set.copyOf(o);
        }
    }

    /** @param security declared security schemes, for the caller to configure credentials */
    public record Result(String title, String version, String description, String baseUrl, List<McpTool> tools,
            List<String> warnings, List<Map<String, Object>> security) {
    }

    public static final class SpecException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public SpecException(String message) {
            super(message);
        }
    }

    // ------------------------------------------------------------------ entry

    public static Result generate(String text, Options opts) {
        Map<String, Object> doc = parse(text);
        boolean swagger2 = "2.0".equals(doc.get("swagger"));
        boolean oas3 = doc.get("openapi") instanceof String v && v.startsWith("3.");
        if (!swagger2 && !oas3) {
            throw new SpecException("Not a Swagger 2.0 or OpenAPI 3.x document (missing 'swagger: \"2.0\"' or 'openapi: 3.x')");
        }
        if (!(doc.get("paths") instanceof Map<?, ?> paths) || paths.isEmpty()) {
            throw new SpecException("The document has no 'paths'");
        }
        Gen g = new Gen(doc, swagger2, opts);
        return g.run(paths);
    }

    // ------------------------------------------------------------------ parsing

    @SuppressWarnings("unchecked")
    static Map<String, Object> parse(String text) {
        if (text == null || text.isBlank()) {
            throw new SpecException("Empty specification");
        }
        if (text.length() > MAX_SPEC_CHARS) {
            throw new SpecException("Specification larger than " + MAX_SPEC_CHARS + " characters");
        }
        Object root;
        try {
            String t = text.stripLeading();
            if (t.startsWith("{")) {
                root = Json.read(t);
            } else {
                LoaderOptions lo = new LoaderOptions();
                lo.setMaxAliasesForCollections(50);
                lo.setAllowDuplicateKeys(false);
                lo.setCodePointLimit(MAX_SPEC_CHARS);
                root = new Yaml(new SafeConstructor(lo)).load(t);
            }
        } catch (RuntimeException e) {
            throw new SpecException("Cannot parse the specification as JSON or YAML: " + firstLine(e.getMessage()));
        }
        Object n = normalize(root, 0);
        if (!(n instanceof Map<?, ?>)) {
            throw new SpecException("The specification must be an object");
        }
        return (Map<String, Object>) n;
    }

    private static String firstLine(String s) {
        if (s == null) {
            return "";
        }
        int i = s.indexOf('\n');
        String l = i < 0 ? s : s.substring(0, i);
        return l.length() > 200 ? l.substring(0, 200) : l;
    }

    /** YAML gives non-string keys and dates; JSON-ify. */
    private static Object normalize(Object o, int depth) {
        if (depth > 64) {
            throw new SpecException("Specification nested too deeply");
        }
        if (o instanceof Map<?, ?> m) {
            Map<String, Object> out = new LinkedHashMap<>();
            m.forEach((k, v) -> out.put(String.valueOf(k), normalize(v, depth + 1)));
            return out;
        }
        if (o instanceof List<?> l) {
            List<Object> out = new ArrayList<>(l.size());
            l.forEach(v -> out.add(normalize(v, depth + 1)));
            return out;
        }
        if (o instanceof Date d) {
            return d.toInstant().toString();
        }
        if (o == null || o instanceof String || o instanceof Number || o instanceof Boolean) {
            return o;
        }
        return String.valueOf(o);
    }

    // ------------------------------------------------------------------ generator

    private static final class Gen {
        private final Map<String, Object> doc;
        private final boolean swagger2;
        private final Options opts;
        private final List<String> warnings = new ArrayList<>();
        private final Set<String> usedNames = new HashSet<>();
        private final List<Pattern> include = new ArrayList<>();
        private final List<Pattern> exclude = new ArrayList<>();

        Gen(Map<String, Object> doc, boolean swagger2, Options opts) {
            this.doc = doc;
            this.swagger2 = swagger2;
            this.opts = opts == null ? Options.defaults() : opts;
            for (String r : this.opts.includePaths()) {
                include.add(regex(r));
            }
            for (String r : this.opts.excludePaths()) {
                exclude.add(regex(r));
            }
        }

        private static Pattern regex(String r) {
            if (r.length() > 200) {
                throw new SpecException("Path filter too long");
            }
            try {
                return Pattern.compile(r);
            } catch (RuntimeException e) {
                throw new SpecException("Invalid path filter regex: " + r);
            }
        }

        Result run(Map<?, ?> paths) {
            Map<String, Object> info = map(doc.get("info"));
            List<McpTool> tools = new ArrayList<>();
            String base = baseUrl();
            for (var pe : paths.entrySet()) {
                String path = String.valueOf(pe.getKey());
                if (!(pe.getValue() instanceof Map<?, ?> item)) {
                    continue;
                }
                if (!path.startsWith("/") || path.contains("?") || path.contains("#") || path.contains("..")) {
                    warnings.add("Skipped path '" + clip(path, 80) + "': not a plain absolute path");
                    continue;
                }
                if (!include.isEmpty() && include.stream().noneMatch(p -> p.matcher(path).find())
                        || exclude.stream().anyMatch(p -> p.matcher(path).find())) {
                    continue;
                }
                for (String method : HTTP_METHODS) {
                    if (!(item.get(method) instanceof Map<?, ?> opRaw)) {
                        continue;
                    }
                    String up = method.toUpperCase(Locale.ROOT);
                    if (!opts.methods().contains(up)) {
                        continue;
                    }
                    @SuppressWarnings("unchecked")
                    Map<String, Object> op = (Map<String, Object>) opRaw;
                    if (!opts.includeDeprecated() && Boolean.TRUE.equals(op.get("deprecated"))) {
                        continue;
                    }
                    if (!tagsOk(op)) {
                        continue;
                    }
                    if (tools.size() >= opts.maxTools()) {
                        warnings.add("Stopped at maxTools=" + opts.maxTools() + "; narrow the filters to include the rest");
                        return done(info, base, tools);
                    }
                    try {
                        McpTool t = tool(path, up, op, map(item));
                        if (t != null) {
                            tools.add(t);
                        }
                    } catch (SpecException e) {
                        warnings.add("Skipped " + up + " " + path + ": " + e.getMessage());
                    }
                }
            }
            if (tools.isEmpty()) {
                warnings.add("No operations matched; by default only GET/HEAD are exposed (set methods to include POST, PUT, PATCH, DELETE)");
            }
            return done(info, base, tools);
        }

        private Result done(Map<String, Object> info, String base, List<McpTool> tools) {
            return new Result(str(info.get("title")), str(info.get("version")), clip(str(info.get("description")), 2000), base, tools,
                    List.copyOf(warnings), security());
        }

        private boolean tagsOk(Map<String, Object> op) {
            List<String> tags = new ArrayList<>();
            if (op.get("tags") instanceof List<?> l) {
                l.forEach(t -> tags.add(String.valueOf(t)));
            }
            if (!opts.includeTags().isEmpty() && tags.stream().noneMatch(opts.includeTags()::contains)) {
                return false;
            }
            return tags.stream().noneMatch(opts.excludeTags()::contains);
        }

        // ---- base url and security

        private String baseUrl() {
            if (opts.baseUrl() != null && !opts.baseUrl().isBlank()) {
                return trimSlash(opts.baseUrl().trim());
            }
            String origin = origin(opts.sourceUrl());
            if (swagger2) {
                String host = str(doc.get("host"));
                String bp = str(doc.get("basePath"));
                String scheme = "https";
                if (doc.get("schemes") instanceof List<?> s && !s.isEmpty() && !s.contains("https")) {
                    scheme = String.valueOf(s.get(0));
                }
                if (host.isBlank()) {
                    if (origin == null) {
                        warnings.add("The spec has no host; set baseUrl");
                        return "";
                    }
                    return trimSlash(origin + (bp.startsWith("/") ? bp : ""));
                }
                return trimSlash(scheme + "://" + host + (bp.startsWith("/") ? bp : ""));
            }
            if (doc.get("servers") instanceof List<?> sv && !sv.isEmpty() && sv.get(0) instanceof Map<?, ?> s0) {
                String url = str(s0.get("url"));
                if (s0.get("variables") instanceof Map<?, ?> vars) {
                    for (var v : vars.entrySet()) {
                        if (v.getValue() instanceof Map<?, ?> vm && vm.get("default") != null) {
                            url = url.replace("{" + v.getKey() + "}", String.valueOf(vm.get("default")));
                        }
                    }
                }
                if (url.startsWith("/")) {
                    if (origin != null) {
                        return trimSlash(origin + url);
                    }
                    warnings.add("The spec's server URL is relative; set baseUrl or import it from a URL");
                    return "";
                }
                if (url.startsWith("http://") || url.startsWith("https://")) {
                    return trimSlash(url);
                }
            }
            if (origin != null) {
                return origin;
            }
            warnings.add("The spec declares no server; set baseUrl");
            return "";
        }

        private static String origin(String url) {
            if (url == null || url.isBlank()) {
                return null;
            }
            try {
                URI u = URI.create(url.trim());
                if (u.getScheme() == null || u.getHost() == null) {
                    return null;
                }
                return u.getScheme() + "://" + u.getHost() + (u.getPort() > 0 ? ":" + u.getPort() : "");
            } catch (IllegalArgumentException e) {
                return null;
            }
        }

        private static String trimSlash(String s) {
            String r = s;
            while (r.endsWith("/")) {
                r = r.substring(0, r.length() - 1);
            }
            return r;
        }

        private List<Map<String, Object>> security() {
            Map<String, Object> schemes = map(swagger2 ? doc.get("securityDefinitions") : map(doc.get("components")).get("securitySchemes"));
            List<Map<String, Object>> out = new ArrayList<>();
            for (var e : schemes.entrySet()) {
                Map<String, Object> s = map(resolve(e.getValue()));
                Map<String, Object> m = new LinkedHashMap<>();
                m.put("name", e.getKey());
                String type = str(s.get("type"));
                if (swagger2 && type.equals("basic")) {
                    m.put("type", "http");
                    m.put("scheme", "basic");
                } else {
                    m.put("type", type);
                    if (!str(s.get("scheme")).isEmpty()) {
                        m.put("scheme", str(s.get("scheme")));
                    }
                }
                if (!str(s.get("in")).isEmpty()) {
                    m.put("in", str(s.get("in")));
                    m.put("parameterName", str(s.get("name")));
                }
                out.add(m);
            }
            return out;
        }

        // ---- refs

        private Object resolve(Object node) {
            Object cur = node;
            for (int i = 0; i < 20 && cur instanceof Map<?, ?> m && m.get("$ref") instanceof String ref; i++) {
                Object t = lookup(ref);
                if (t == null) {
                    return Map.of();
                }
                cur = t;
            }
            return cur;
        }

        private Object lookup(String ref) {
            if (!ref.startsWith("#/")) {
                warn("External $ref not followed: " + clip(ref, 80));
                return null;
            }
            Object cur = doc;
            for (String part : ref.substring(2).split("/")) {
                String key = java.net.URLDecoder.decode(part.replace("+", "%2B"), StandardCharsets.UTF_8).replace("~1", "/").replace("~0", "~");
                if (!(cur instanceof Map<?, ?> m) || !m.containsKey(key)) {
                    warn("Unresolved $ref " + clip(ref, 80));
                    return null;
                }
                cur = m.get(key);
            }
            return cur;
        }

        private void warn(String w) {
            if (!warnings.contains(w) && warnings.size() < 200) {
                warnings.add(w);
            }
        }

        // ---- operation

        private McpTool tool(String path, String method, Map<String, Object> op, Map<String, Object> item) {
            // parameters: path level, overridden by operation level (same name + in)
            Map<String, Map<String, Object>> params = new LinkedHashMap<>();
            for (Object src : List.of(list(item.get("parameters")), list(op.get("parameters")))) {
                for (Object p : (List<?>) src) {
                    Map<String, Object> pm = map(resolve(p));
                    if (!str(pm.get("name")).isEmpty() && !str(pm.get("in")).isEmpty()) {
                        params.put(str(pm.get("in")) + ":" + str(pm.get("name")), pm);
                    }
                }
            }
            Map<String, Object> props = new LinkedHashMap<>();
            List<String> required = new ArrayList<>();
            List<McpTool.Param> bound = new ArrayList<>();
            Set<String> args = new HashSet<>();
            Set<String> pathVars = new LinkedHashSet<>();
            var m = PATH_VAR.matcher(path);
            while (m.find()) {
                pathVars.add(m.group(1));
            }
            Set<String> seenPath = new HashSet<>();
            Map<String, Object> formProps = new LinkedHashMap<>();
            List<String> formRequired = new ArrayList<>();
            Map<String, Object> swaggerBody = null;
            boolean swaggerBodyRequired = false;

            for (Map<String, Object> p : params.values()) {
                String in = str(p.get("in"));
                String name = str(p.get("name"));
                boolean req = Boolean.TRUE.equals(p.get("required")) || in.equals("path");
                switch (in) {
                    case "path", "query", "header" -> {
                        if (in.equals("header") && SKIPPED_HEADERS.contains(name.toLowerCase(Locale.ROOT))) {
                            continue;
                        }
                        if (in.equals("header") && !NAME_OK.matcher(name).matches()) {
                            continue;
                        }
                        Map<String, Object> schema = paramSchema(p);
                        String arg = argName(name, in, args);
                        if (!str(p.get("description")).isEmpty() && !schema.containsKey("description")) {
                            schema.put("description", clip(clean(str(p.get("description"))), 300));
                        }
                        props.put(arg, schema);
                        if (req) {
                            required.add(arg);
                        }
                        bound.add(new McpTool.Param(arg, name, in, req));
                        if (in.equals("path")) {
                            seenPath.add(name);
                        }
                    }
                    case "formData" -> {
                        if ("file".equals(str(p.get("type")))) {
                            throw new SpecException("file uploads are not supported");
                        }
                        formProps.put(name, paramSchema(p));
                        if (req) {
                            formRequired.add(name);
                        }
                    }
                    case "body" -> {
                        swaggerBody = map(p.get("schema"));
                        swaggerBodyRequired = Boolean.TRUE.equals(p.get("required"));
                    }
                    default -> {
                        // cookie params are not exposed
                        warn("Cookie parameters are not exposed (" + clip(name, 40) + ")");
                    }
                }
            }
            for (String v : pathVars) {
                if (!seenPath.contains(v)) {
                    String arg = argName(v, "path", args);
                    props.put(arg, new LinkedHashMap<>(Map.of("type", "string")));
                    required.add(arg);
                    bound.add(new McpTool.Param(arg, v, "path", true));
                }
            }

            // body
            String bodyArg = null;
            String bodyType = null;
            Map<String, Object> bodySchema = null;
            boolean bodyRequired = false;
            if (swagger2) {
                List<String> consumes = new ArrayList<>();
                for (Object c : list(op.get("consumes"))) {
                    consumes.add(String.valueOf(c));
                }
                if (consumes.isEmpty()) {
                    for (Object c : list(doc.get("consumes"))) {
                        consumes.add(String.valueOf(c));
                    }
                }
                if (swaggerBody != null) {
                    bodyType = pickJson(consumes);
                    if (bodyType == null) {
                        throw new SpecException("body content type " + consumes + " is not JSON");
                    }
                    bodySchema = schema(swaggerBody, true);
                    bodyRequired = swaggerBodyRequired;
                } else if (!formProps.isEmpty()) {
                    if (!consumes.isEmpty() && !consumes.contains("application/x-www-form-urlencoded")) {
                        throw new SpecException("form body type " + consumes + " is not supported (urlencoded only)");
                    }
                    bodyType = "application/x-www-form-urlencoded";
                    bodySchema = new LinkedHashMap<>();
                    bodySchema.put("type", "object");
                    bodySchema.put("properties", formProps);
                    if (!formRequired.isEmpty()) {
                        bodySchema.put("required", formRequired);
                    }
                    bodyRequired = !formRequired.isEmpty();
                }
            } else if (op.get("requestBody") != null) {
                Map<String, Object> rb = map(resolve(op.get("requestBody")));
                Map<String, Object> content = map(rb.get("content"));
                String pick = null;
                for (String ct : content.keySet()) {
                    if (isJson(ct)) {
                        pick = ct;
                        break;
                    }
                }
                if (pick == null && content.containsKey("application/x-www-form-urlencoded")) {
                    pick = "application/x-www-form-urlencoded";
                }
                if (pick == null) {
                    throw new SpecException("request body types " + content.keySet() + " are not supported (JSON or urlencoded only)");
                }
                bodyType = pick;
                bodySchema = schema(map(content.get(pick)).get("schema"), true);
                bodyRequired = Boolean.TRUE.equals(rb.get("required"));
            }
            if (bodySchema != null) {
                bodyArg = argName("body", "body", args);
                if (!str(op.get("description")).isEmpty() && !bodySchema.containsKey("description")) {
                    bodySchema.put("description", "Request body (" + bodyType + ")");
                }
                props.put(bodyArg, bodySchema);
                if (bodyRequired) {
                    required.add(bodyArg);
                }
            }

            Map<String, Object> input = new LinkedHashMap<>();
            input.put("type", "object");
            input.put("properties", props);
            if (!required.isEmpty()) {
                input.put("required", required);
            }
            input.put("additionalProperties", false);
            if (Json.write(input).length() > MAX_SCHEMA_JSON) {
                throw new SpecException("input schema is larger than " + MAX_SCHEMA_JSON + " characters");
            }

            Map<String, Object> output = outputSchema(op);
            String name = name(op, method, path);
            String desc = description(op, method, path);
            boolean read = method.equals("GET") || method.equals("HEAD");
            McpTool.Hints hints = new McpTool.Hints(read, method.equals("DELETE"),
                    read || method.equals("PUT") || method.equals("DELETE"));
            return new McpTool(name, desc, input, output, hints, new McpTool.Binding(method, path, bound, bodyArg, bodyType));
        }

        private static boolean isJson(String ct) {
            String c = ct.toLowerCase(Locale.ROOT);
            int i = c.indexOf(';');
            if (i >= 0) {
                c = c.substring(0, i).trim();
            }
            return c.equals("application/json") || c.endsWith("+json");
        }

        private static String pickJson(List<String> consumes) {
            if (consumes.isEmpty()) {
                return "application/json";
            }
            for (String c : consumes) {
                if (isJson(c)) {
                    return c.contains(";") ? c.substring(0, c.indexOf(';')).trim() : c;
                }
            }
            return null;
        }

        private String argName(String raw, String in, Set<String> used) {
            String base = raw.replaceAll("[^A-Za-z0-9_.-]", "_");
            if (base.isEmpty()) {
                base = "param";
            }
            if (base.length() > 60) {
                base = base.substring(0, 60);
            }
            String n = base;
            if (!used.add(n)) {
                n = base + "_" + in;
                int i = 2;
                while (!used.add(n)) {
                    n = base + "_" + in + i++;
                }
            }
            return n;
        }

        private Map<String, Object> paramSchema(Map<String, Object> p) {
            Map<String, Object> s;
            if (p.get("schema") != null) {
                s = schema(p.get("schema"), true);
            } else if (p.get("content") instanceof Map<?, ?> c && !c.isEmpty()) {
                s = schema(map(c.values().iterator().next()).get("schema"), true);
            } else {
                s = schema(p, true); // Swagger 2: the parameter itself carries type/items/enum
                s.remove("required");
                s.remove("name");
            }
            if (s.isEmpty()) {
                s.put("type", "string");
            }
            return s;
        }

        private Map<String, Object> outputSchema(Map<String, Object> op) {
            Map<String, Object> responses = map(op.get("responses"));
            List<String> codes = new ArrayList<>(responses.keySet());
            codes.sort(null);
            for (String code : codes) {
                if (!code.startsWith("2")) {
                    continue;
                }
                Map<String, Object> r = map(resolve(responses.get(code)));
                Object sch = null;
                if (swagger2) {
                    sch = r.get("schema");
                } else {
                    for (var e : map(r.get("content")).entrySet()) {
                        if (isJson(e.getKey())) {
                            sch = map(e.getValue()).get("schema");
                            break;
                        }
                    }
                }
                if (sch != null) {
                    Map<String, Object> s = schema(sch, false);
                    if ("object".equals(s.get("type")) && Json.write(s).length() <= MAX_SCHEMA_JSON) {
                        return s;
                    }
                }
                return null;
            }
            return null;
        }

        private String name(Map<String, Object> op, String method, String path) {
            String id = str(op.get("operationId"));
            String base;
            if (!id.isEmpty()) {
                base = id.replaceAll("[^A-Za-z0-9_-]", "_");
            } else {
                String p = path.replace("{", "").replace("}", "").replaceAll("[^A-Za-z0-9]+", "_").replaceAll("^_+|_+$", "");
                base = method.toLowerCase(Locale.ROOT) + (p.isEmpty() ? "" : "_" + p);
            }
            base = opts.namePrefix() + base;
            if (base.length() > 60) {
                base = base.substring(0, 60);
            }
            String n = base;
            int i = 2;
            while (!usedNames.add(n)) {
                n = base + "_" + i++;
            }
            return n;
        }

        private String description(Map<String, Object> op, String method, String path) {
            String s = clean(str(op.get("summary")));
            String d = clean(str(op.get("description")));
            StringBuilder b = new StringBuilder();
            if (!s.isEmpty()) {
                b.append(s);
            }
            if (!d.isEmpty() && !d.equals(s)) {
                b.append(b.length() > 0 ? "\n\n" : "").append(d);
            }
            b.append(b.length() > 0 ? "\n\n" : "").append("Calls ").append(method).append(' ').append(path);
            return clip(b.toString(), 1000);
        }

        // ---- schemas

        Map<String, Object> schema(Object node, boolean forRequest) {
            return schema(node, forRequest, new ArrayList<>(), 0);
        }

        @SuppressWarnings("unchecked")
        private Map<String, Object> schema(Object node, boolean forRequest, List<String> stack, int depth) {
            if (!(node instanceof Map<?, ?> raw)) {
                return new LinkedHashMap<>();
            }
            Map<String, Object> n = (Map<String, Object>) raw;
            if (depth > MAX_SCHEMA_DEPTH) {
                return new LinkedHashMap<>(Map.of("description", "(nested too deeply, truncated)"));
            }
            if (n.get("$ref") instanceof String ref) {
                if (stack.contains(ref)) {
                    return new LinkedHashMap<>(Map.of("type", "object", "description", "(recursive reference, truncated)"));
                }
                Object target = lookup(ref);
                if (target == null) {
                    return new LinkedHashMap<>();
                }
                stack.add(ref);
                try {
                    return schema(target, forRequest, stack, depth + 1);
                } finally {
                    stack.remove(stack.size() - 1);
                }
            }
            Map<String, Object> out = new LinkedHashMap<>();
            for (var e : n.entrySet()) {
                if (COPY_KEYS.contains(e.getKey())) {
                    Object v = e.getValue();
                    out.put(e.getKey(), "description".equals(e.getKey()) || "title".equals(e.getKey()) ? clip(clean(String.valueOf(v)), 500) : v);
                }
            }
            if (n.get("exclusiveMinimum") instanceof Number x) {
                out.put("exclusiveMinimum", x);
            }
            if (n.get("exclusiveMaximum") instanceof Number x) {
                out.put("exclusiveMaximum", x);
            }
            if (n.containsKey("example") && !(n.get("example") instanceof Map) && !(n.get("example") instanceof List)) {
                out.put("examples", List.of(n.get("example")));
            }
            if (Boolean.TRUE.equals(n.get("nullable")) && out.get("type") instanceof String t) {
                out.put("type", List.of(t, "null"));
            }
            if (n.get("properties") instanceof Map<?, ?> pm) {
                Map<String, Object> props = new LinkedHashMap<>();
                Set<String> dropped = new HashSet<>();
                for (var pe : pm.entrySet()) {
                    String key = String.valueOf(pe.getKey());
                    Map<String, Object> pr = map(resolve(pe.getValue()));
                    if (forRequest && Boolean.TRUE.equals(pr.get("readOnly")) || !forRequest && Boolean.TRUE.equals(pr.get("writeOnly"))) {
                        dropped.add(key);
                        continue;
                    }
                    props.put(key, schema(pe.getValue(), forRequest, stack, depth + 1));
                }
                out.put("properties", props);
                if (!out.containsKey("type")) {
                    out.put("type", "object");
                }
                if (n.get("required") instanceof List<?> rl) {
                    List<Object> req = new ArrayList<>();
                    for (Object r : rl) {
                        if (!dropped.contains(String.valueOf(r))) {
                            req.add(r);
                        }
                    }
                    if (!req.isEmpty()) {
                        out.put("required", req);
                    }
                }
            } else if (n.get("required") instanceof List<?> rl && !rl.isEmpty() && "object".equals(out.get("type"))) {
                out.put("required", rl);
            }
            if (n.get("items") != null) {
                out.put("items", schema(n.get("items"), forRequest, stack, depth + 1));
                if (!out.containsKey("type")) {
                    out.put("type", "array");
                }
            }
            if (n.get("additionalProperties") instanceof Map) {
                out.put("additionalProperties", schema(n.get("additionalProperties"), forRequest, stack, depth + 1));
            } else if (n.get("additionalProperties") instanceof Boolean b) {
                out.put("additionalProperties", b);
            }
            if (n.get("allOf") instanceof List<?> all) {
                mergeAllOf(out, all, forRequest, stack, depth);
            }
            for (String k : List.of("oneOf", "anyOf")) {
                if (n.get(k) instanceof List<?> l) {
                    List<Object> parts = new ArrayList<>();
                    for (Object o : l) {
                        parts.add(schema(o, forRequest, stack, depth + 1));
                    }
                    out.put(k, parts);
                }
            }
            if (out.get("enum") instanceof List<?> en && en.size() > 500) {
                out.put("enum", new ArrayList<>(en.subList(0, 500)));
            }
            return out;
        }

        @SuppressWarnings("unchecked")
        private void mergeAllOf(Map<String, Object> out, List<?> all, boolean forRequest, List<String> stack, int depth) {
            List<Map<String, Object>> parts = new ArrayList<>();
            for (Object o : all) {
                parts.add(schema(o, forRequest, stack, depth + 1));
            }
            boolean mergeable = parts.stream().allMatch(p -> p.get("type") == null || "object".equals(p.get("type")));
            if (!mergeable) {
                out.put("allOf", new ArrayList<Object>(parts));
                return;
            }
            Map<String, Object> props = out.get("properties") instanceof Map<?, ?> pm ? new LinkedHashMap<>((Map<String, Object>) pm) : new LinkedHashMap<>();
            Set<Object> req = out.get("required") instanceof List<?> rl ? new LinkedHashSet<>(rl) : new LinkedHashSet<>();
            for (Map<String, Object> p : parts) {
                if (p.get("properties") instanceof Map<?, ?> pm) {
                    props.putAll((Map<String, Object>) pm);
                }
                if (p.get("required") instanceof List<?> rl) {
                    req.addAll(rl);
                }
                p.forEach((k, v) -> {
                    if (!k.equals("properties") && !k.equals("required")) {
                        out.putIfAbsent(k, v);
                    }
                });
            }
            out.put("type", "object");
            out.put("properties", props);
            req.removeIf(r -> !props.containsKey(String.valueOf(r)));
            if (!req.isEmpty()) {
                out.put("required", new ArrayList<>(req));
            }
        }
    }

    // ------------------------------------------------------------------ small helpers

    @SuppressWarnings("unchecked")
    private static Map<String, Object> map(Object o) {
        return o instanceof Map<?, ?> m ? (Map<String, Object>) m : new LinkedHashMap<>();
    }

    private static List<?> list(Object o) {
        return o instanceof List<?> l ? l : List.of();
    }

    private static String str(Object o) {
        return o == null ? "" : String.valueOf(o);
    }

    private static String clean(String s) {
        return s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]", " ").strip();
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }
}
