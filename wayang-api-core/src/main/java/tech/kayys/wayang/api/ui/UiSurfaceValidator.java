package tech.kayys.wayang.api.ui;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Checks a declarative UI surface (the A2UI wire format: {@code {id, title, rootNode, metadata}} with {@code _type}-tagged nodes) that
 * an agent or a process produced, before anything shows it. The input is untrusted, so this accepts a deliberately small, read-only
 * subset and returns a rebuilt copy containing only known fields: unknown node types, unknown fields, actions that do something,
 * images (which would make the viewer's device fetch an address the agent chose), and anything over the size limits are refused with a
 * problem that says where.
 *
 * <p>
 * Supported nodes: {@code text, heading, card, container, table, markdown (shown as plain text), list, badge, divider, metadata_row,
 * provenance, button}. A button may only carry a {@code refresh} action or a {@code navigate} to a route inside the app (starting with
 * {@code /}); {@code submit}, {@code invoke}, {@code approve} and {@code reject} need a binding to something the viewer is allowed to
 * act on and are not accepted yet.
 */
public final class UiSurfaceValidator {

    public static final int MAX_DEPTH = 8;
    public static final int MAX_NODES = 300;
    public static final int MAX_TEXT = 10_000;
    public static final int MAX_SHORT = 200;
    public static final int MAX_ROWS = 200;
    public static final int MAX_COLUMNS = 20;
    public static final int MAX_METADATA = 20;

    private static final Set<String> BADGE = Set.of("neutral", "info", "success", "warning", "error");

    /** @param surface the rebuilt, safe copy (null when there are problems) */
    public record Result(boolean ok, List<String> problems, Map<String, Object> surface) {
    }

    private final List<String> problems = new ArrayList<>();
    private int nodes;

    private UiSurfaceValidator() {
    }

    public static Result validate(Object raw) {
        UiSurfaceValidator v = new UiSurfaceValidator();
        if (raw instanceof Map<?, ?> m && m.containsKey("components")) {
            raw = A2uiStandardAdapter.adapt(m, v.problems); // A2UI standard-catalog shape: convert, then check like any surface
            if (!v.problems.isEmpty()) {
                return new Result(false, List.copyOf(v.problems), null);
            }
        }
        Map<String, Object> out = v.surface(raw);
        return v.problems.isEmpty() ? new Result(true, List.of(), out) : new Result(false, List.copyOf(v.problems), null);
    }

    private Map<String, Object> surface(Object raw) {
        if (!(raw instanceof Map<?, ?> m)) {
            problem("surface", "must be an object");
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", shortText(m.get("id"), "surface.id", true));
        out.put("title", shortText(m.get("title"), "surface.title", true));
        out.put("rootNode", node(m.get("rootNode"), "rootNode", 1));
        Object meta = m.get("metadata");
        Map<String, Object> md = new LinkedHashMap<>();
        if (meta != null) {
            if (meta instanceof Map<?, ?> mm) {
                if (mm.size() > MAX_METADATA) {
                    problem("metadata", "more than " + MAX_METADATA + " entries");
                } else {
                    for (var e : mm.entrySet()) {
                        String k = String.valueOf(e.getKey());
                        if (e.getValue() instanceof String || e.getValue() instanceof Number || e.getValue() instanceof Boolean) {
                            md.put(limit(k, MAX_SHORT), e.getValue() instanceof String s ? limit(s, MAX_SHORT) : e.getValue());
                        } else if (e.getValue() != null) {
                            problem("metadata." + k, "only text, numbers and booleans are accepted");
                        }
                    }
                }
            } else {
                problem("metadata", "must be an object");
            }
        }
        out.put("metadata", md);
        return out;
    }

    private Map<String, Object> node(Object raw, String path, int depth) {
        if (!(raw instanceof Map<?, ?> m)) {
            problem(path, "must be an object");
            return null;
        }
        if (depth > MAX_DEPTH) {
            problem(path, "nested deeper than " + MAX_DEPTH + " levels");
            return null;
        }
        if (++nodes > MAX_NODES) {
            if (nodes == MAX_NODES + 1) {
                problem(path, "more than " + MAX_NODES + " nodes");
            }
            return null;
        }
        Object t = m.get("_type");
        if (!(t instanceof String type)) {
            problem(path, "has no _type");
            return null;
        }
        Map<String, Object> o = new LinkedHashMap<>();
        o.put("_type", type);
        if (m.get("id") != null) {
            o.put("id", shortText(m.get("id"), path + ".id", false));
        }
        switch (type) {
            case "text" -> {
                o.put("text", text(m.get("text"), path + ".text"));
                o.put("secondary", Boolean.TRUE.equals(m.get("secondary")));
            }
            case "heading" -> {
                o.put("text", text(m.get("text"), path + ".text"));
                Object l = m.get("level");
                int level = l instanceof Number n ? n.intValue() : 1;
                o.put("level", Math.max(1, Math.min(6, level)));
            }
            case "markdown" -> o.put("markdown", text(m.get("markdown"), path + ".markdown"));
            case "badge" -> {
                o.put("label", shortText(m.get("label"), path + ".label", false));
                String variant = m.get("variant") instanceof String s ? s : "neutral";
                o.put("variant", BADGE.contains(variant) ? variant : "neutral");
            }
            case "divider" -> {
            }
            case "metadata_row" -> {
                o.put("label", shortText(m.get("label"), path + ".label", false));
                o.put("value", text(m.get("value"), path + ".value"));
            }
            case "provenance" -> {
                o.put("evidenceId", shortText(m.get("evidenceId"), path + ".evidenceId", false));
                o.put("citation", text(m.get("citation"), path + ".citation"));
                if (m.get("confidence") instanceof Number c) {
                    o.put("confidence", Math.max(0.0, Math.min(1.0, c.doubleValue())));
                }
            }
            case "card" -> {
                if (m.get("title") != null) {
                    o.put("title", shortText(m.get("title"), path + ".title", false));
                }
                o.put("children", children(m.get("children"), path + ".children", depth));
            }
            case "container" -> {
                o.put("children", children(m.get("children"), path + ".children", depth));
                o.put("row", Boolean.TRUE.equals(m.get("row")));
            }
            case "list" -> {
                o.put("items", children(m.get("items"), path + ".items", depth));
                o.put("ordered", Boolean.TRUE.equals(m.get("ordered")));
            }
            case "table" -> table(m, o, path);
            case "button" -> {
                o.put("label", shortText(m.get("label"), path + ".label", true));
                o.put("action", action(m.get("action"), path + ".action"));
                o.put("primary", !Boolean.FALSE.equals(m.get("primary")));
            }
            case "image", "chart", "artifact" ->
                problem(path, "'" + type + "' nodes are not supported yet");
            default -> problem(path, "unknown node type '" + limit(type, 40) + "'");
        }
        return o;
    }

    private List<Object> children(Object raw, String path, int depth) {
        List<Object> out = new ArrayList<>();
        if (raw == null) {
            return out;
        }
        if (!(raw instanceof List<?> l)) {
            problem(path, "must be a list");
            return out;
        }
        for (int i = 0; i < l.size() && nodes <= MAX_NODES; i++) {
            Map<String, Object> c = node(l.get(i), path + "[" + i + "]", depth + 1);
            if (c != null) {
                out.add(c);
            }
        }
        return out;
    }

    private void table(Map<?, ?> m, Map<String, Object> o, String path) {
        List<String> headers = new ArrayList<>();
        if (m.get("headers") instanceof List<?> h) {
            if (h.size() > MAX_COLUMNS) {
                problem(path + ".headers", "more than " + MAX_COLUMNS + " columns");
            } else {
                h.forEach(x -> headers.add(limit(String.valueOf(x), MAX_SHORT)));
            }
        } else if (m.get("headers") != null) {
            problem(path + ".headers", "must be a list");
        }
        List<List<String>> rows = new ArrayList<>();
        if (m.get("rows") instanceof List<?> r) {
            if (r.size() > MAX_ROWS) {
                problem(path + ".rows", "more than " + MAX_ROWS + " rows");
            } else {
                for (Object row : r) {
                    if (row instanceof List<?> cells && cells.size() <= MAX_COLUMNS) {
                        List<String> rr = new ArrayList<>();
                        cells.forEach(x -> rr.add(limit(String.valueOf(x), MAX_SHORT)));
                        rows.add(rr);
                    } else {
                        problem(path + ".rows", "every row must be a list of at most " + MAX_COLUMNS + " cells");
                        break;
                    }
                }
            }
        } else if (m.get("rows") != null) {
            problem(path + ".rows", "must be a list");
        }
        o.put("headers", headers);
        o.put("rows", rows);
    }

    private Map<String, Object> action(Object raw, String path) {
        Map<String, Object> a = new LinkedHashMap<>();
        if (!(raw instanceof Map<?, ?> m)) {
            problem(path, "must be an object");
            return a;
        }
        Object t = m.get("_type");
        String type = t instanceof String s ? s : "refresh";
        a.put("_type", type);
        switch (type) {
            case "refresh" -> {
            }
            case "navigate" -> {
                String route = m.get("route") instanceof String s ? s : "";
                if (!route.startsWith("/") || route.startsWith("//") || route.length() > MAX_SHORT || route.contains("\\") || route.chars().anyMatch(c -> c < 32)) {
                    problem(path + ".route", "must be a route inside the app, starting with a single '/'");
                }
                a.put("route", limit(route, MAX_SHORT));
            }
            case "submit", "invoke", "approve", "reject" ->
                problem(path, "'" + type + "' actions are not supported yet: they need a binding to something the viewer may act on");
            default -> problem(path, "unknown action '" + limit(type, 40) + "'");
        }
        return a;
    }

    private String text(Object v, String path) {
        if (v == null) {
            return "";
        }
        if (!(v instanceof String s)) {
            problem(path, "must be text");
            return "";
        }
        if (s.length() > MAX_TEXT) {
            problem(path, "longer than " + MAX_TEXT + " characters");
            return "";
        }
        return s;
    }

    private String shortText(Object v, String path, boolean required) {
        if (v == null || v instanceof String s && s.isBlank()) {
            if (required) {
                problem(path, "is required");
            }
            return "";
        }
        if (!(v instanceof String s)) {
            problem(path, "must be text");
            return "";
        }
        if (s.length() > MAX_SHORT) {
            problem(path, "longer than " + MAX_SHORT + " characters");
            return "";
        }
        return s;
    }

    private static String limit(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }

    private void problem(String path, String what) {
        if (problems.size() < 50) {
            problems.add(path + ": " + what);
        }
    }
}
