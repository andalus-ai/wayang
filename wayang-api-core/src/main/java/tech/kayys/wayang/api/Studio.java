package tech.kayys.wayang.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Studio-only metadata that travels inside a definition document under the
 * {@code studio} key and never reaches the compiler or the engines:
 * {@code projectId}, {@code layout} (element id to {x, y}) and {@code tags}.
 * Everything that interprets a document for execution must go through
 * {@link #executable(Map)}.
 */
public final class Studio {

    public static final String KEY = "studio";
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}");
    private static final int MAX_LAYOUT = 5000;
    private static final double LIMIT = 1_000_000;
    private static final int META_MAX_DEPTH = 8;
    private static final int META_MAX_NODES = 20_000;
    private static final int META_MAX_STRING = 2000;

    private Studio() {
    }

    /** The document without Studio metadata, i.e. what compilers and engines may see. */
    public static Map<String, Object> executable(Map<String, Object> doc) {
        if (doc == null || !doc.containsKey(KEY)) {
            return doc;
        }
        Map<String, Object> copy = new LinkedHashMap<>(doc);
        copy.remove(KEY);
        return copy;
    }

    @SuppressWarnings("unchecked")
    public static Map<String, Object> of(Map<String, Object> doc) {
        Object s = doc == null ? null : doc.get(KEY);
        return s instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
    }

    public static String projectId(Map<String, Object> doc) {
        return of(doc).get("projectId") instanceof String s ? s : null;
    }

    /** Rejects malformed or oversized metadata with a precise message. */
    public static void check(Map<String, Object> doc) {
        Object s = doc.get(KEY);
        if (s == null) {
            return;
        }
        if (!(s instanceof Map<?, ?> m)) {
            throw ApiException.badRequest("'studio' must be an object");
        }
        for (Object k : m.keySet()) {
            if (!(k instanceof String key) || !(key.equals("projectId") || key.equals("layout") || key.equals("tags") || key.equals("meta") || key.equals("mcp"))) {
                throw ApiException.badRequest("Unknown studio key '" + k + "' (allowed: projectId, layout, tags, meta, mcp)");
            }
        }
        Object pid = m.get("projectId");
        if (pid != null && !(pid instanceof String p && ID.matcher(p).matches())) {
            throw ApiException.badRequest("studio.projectId must be an id string");
        }
        Object tags = m.get("tags");
        if (tags != null) {
            if (!(tags instanceof List<?> l) || l.size() > 20 || l.stream().anyMatch(t -> !(t instanceof String x) || x.length() > 50)) {
                throw ApiException.badRequest("studio.tags must be at most 20 strings of at most 50 characters");
            }
        }
        Object meta = m.get("meta");
        if (meta != null) {
            if (!(meta instanceof Map<?, ?>)) {
                throw ApiException.badRequest("studio.meta must be an object");
            }
            int[] budget = {META_MAX_NODES};
            checkMeta(meta, 0, budget);
        }
        checkMcp(m.get("mcp"));
        Object layout = m.get("layout");
        if (layout != null) {
            if (!(layout instanceof Map<?, ?> lm) || lm.size() > MAX_LAYOUT) {
                throw ApiException.badRequest("studio.layout must be an object with at most " + MAX_LAYOUT + " entries");
            }
            for (Map.Entry<?, ?> e : lm.entrySet()) {
                if (!(e.getValue() instanceof Map<?, ?> pos) || !coordinate(pos.get("x")) || !coordinate(pos.get("y"))) {
                    throw ApiException.badRequest("studio.layout['" + e.getKey() + "'] must be {x, y} with numbers within +-" + (long) LIMIT);
                }
            }
        }
    }

    /** UI-only data (lanes, column labels, ...): plain JSON, bounded depth, size and string length. */
    private static void checkMeta(Object v, int depth, int[] budget) {
        if (depth > META_MAX_DEPTH) {
            throw ApiException.badRequest("studio.meta is nested too deeply (max " + META_MAX_DEPTH + ")");
        }
        if (--budget[0] < 0) {
            throw ApiException.badRequest("studio.meta is too large (max " + META_MAX_NODES + " values)");
        }
        if (v == null || v instanceof Boolean || v instanceof Number) {
            return;
        }
        if (v instanceof String str) {
            if (str.length() > META_MAX_STRING) {
                throw ApiException.badRequest("studio.meta contains a string longer than " + META_MAX_STRING);
            }
            return;
        }
        if (v instanceof Map<?, ?> map) {
            for (Map.Entry<?, ?> e : map.entrySet()) {
                if (!(e.getKey() instanceof String k) || k.length() > 200) {
                    throw ApiException.badRequest("studio.meta keys must be strings of at most 200 characters");
                }
                checkMeta(e.getValue(), depth + 1, budget);
            }
            return;
        }
        if (v instanceof List<?> list) {
            for (Object o : list) {
                checkMeta(o, depth + 1, budget);
            }
            return;
        }
        throw ApiException.badRequest("studio.meta may only contain JSON values");
    }

    /** {@code studio.mcp}: opt-in exposure of a published process as an MCP tool. Strict: unknown keys are errors. */
    private static void checkMcp(Object o) {
        if (o == null) {
            return;
        }
        if (!(o instanceof Map<?, ?> m)) {
            throw ApiException.badRequest("studio.mcp must be an object");
        }
        for (Map.Entry<?, ?> e : m.entrySet()) {
            String k = String.valueOf(e.getKey());
            Object v = e.getValue();
            switch (k) {
                case "expose" -> {
                    if (!(v instanceof Boolean)) {
                        throw ApiException.badRequest("studio.mcp.expose must be true or false");
                    }
                }
                case "description" -> {
                    if (!(v instanceof String d) || d.length() > 1000) {
                        throw ApiException.badRequest("studio.mcp.description must be a string of at most 1000 characters");
                    }
                }
                case "waitSeconds" -> {
                    if (!(v instanceof Number n) || n.doubleValue() < 0 || n.doubleValue() > 60 || n.doubleValue() != Math.floor(n.doubleValue())) {
                        throw ApiException.badRequest("studio.mcp.waitSeconds must be an integer from 0 to 60");
                    }
                }
                case "inputSchema" -> {
                    if (!(v instanceof Map<?, ?>)) {
                        throw ApiException.badRequest("studio.mcp.inputSchema must be a JSON-schema object");
                    }
                    checkMeta(v, 0, new int[] {META_MAX_NODES});
                }
                default -> throw ApiException.badRequest("Unknown studio.mcp key '" + k + "' (allowed: expose, description, waitSeconds, inputSchema)");
            }
        }
    }

    public static Map<String, Object> mcp(Map<String, Object> doc) {
        return of(doc).get("mcp") instanceof Map<?, ?> m ? castMap(m) : Map.of();
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> castMap(Map<?, ?> m) {
        return (Map<String, Object>) m;
    }

    private static boolean coordinate(Object o) {
        return o instanceof Number n && Math.abs(n.doubleValue()) <= LIMIT && !Double.isNaN(n.doubleValue());
    }

    /** A document with its layout filled in (used when importing BPMN without diagram data). */
    public static Map<String, Object> withLayout(Map<String, Object> doc, Map<String, double[]> positions) {
        Map<String, Object> layout = new LinkedHashMap<>();
        positions.forEach((id, p) -> layout.put(id, Map.of("x", p[0], "y", p[1])));
        Map<String, Object> studio = new LinkedHashMap<>(of(doc));
        studio.put("layout", layout);
        Map<String, Object> out = new LinkedHashMap<>(doc);
        out.put(KEY, studio);
        return out;
    }

    /** Names of the keys a client may legitimately send, for documentation. */
    public static List<String> keys() {
        return new ArrayList<>(List.of("projectId", "layout", "tags", "meta", "mcp"));
    }
}
