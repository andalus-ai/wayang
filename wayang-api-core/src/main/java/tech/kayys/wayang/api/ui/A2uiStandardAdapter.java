package tech.kayys.wayang.api.ui;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Reads the A2UI standard-catalog wire shape, as the Andalus framework's {@code andalus-a2ui} module writes it, into Wayang's nested
 * surface form so {@link UiSurfaceValidator} can check it: {@code {"id", "title", "root": "<component id>", "components": [{"id", "component": {"Type": {...}}}]}}.
 *
 * <p>Only a read-only part is understood: {@code Text} (with {@code usageHint} h1..h5 as headings), {@code Column}, {@code Row}, {@code Card} and
 * {@code List} with an {@code explicitList}. Data-model bindings ({@code path}), child templates, buttons and every other type are reported as
 * problems rather than guessed at. Cycles and dangling ids are problems too.
 */
final class A2uiStandardAdapter {

    private final Map<String, Map<String, Object>> byId = new LinkedHashMap<>();
    private final List<String> problems;
    private int visited;

    private A2uiStandardAdapter(List<String> problems) {
        this.problems = problems;
    }

    /** The nested surface for {@code raw} (which has a {@code components} list); problems are appended to {@code problems}. */
    static Map<String, Object> adapt(Map<?, ?> raw, List<String> problems) {
        A2uiStandardAdapter a = new A2uiStandardAdapter(problems);
        Object comps = raw.get("components");
        if (!(comps instanceof List<?> list)) {
            problems.add("components: must be a list");
            return null;
        }
        if (list.size() > UiSurfaceValidator.MAX_NODES) {
            problems.add("components: more than " + UiSurfaceValidator.MAX_NODES + " entries");
            return null;
        }
        for (Object c : list) {
            if (c instanceof Map<?, ?> m && m.get("id") instanceof String id && m.get("component") instanceof Map<?, ?> body && body.size() == 1) {
                @SuppressWarnings("unchecked")
                Map<String, Object> typed = (Map<String, Object>) body;
                if (a.byId.putIfAbsent(id, typed) != null) {
                    problems.add("components: duplicate id");
                }
            } else {
                problems.add("components: each entry needs an id and a component with exactly one type");
            }
        }
        Object root = raw.get("root");
        if (!(root instanceof String rootId) || !a.byId.containsKey(rootId)) {
            problems.add("root: must name a component id");
            return null;
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("id", raw.get("id"));
        out.put("title", raw.get("title"));
        out.put("rootNode", a.node(rootId, new HashSet<>(), 1));
        return out;
    }

    private Map<String, Object> node(String id, Set<String> path, int depth) {
        if (++visited > UiSurfaceValidator.MAX_NODES || depth > UiSurfaceValidator.MAX_DEPTH) {
            problems.add("components: too many or too deeply nested");
            return null;
        }
        if (!path.add(id)) {
            problems.add("components: a component contains itself");
            return null;
        }
        try {
            Map<String, Object> body = byId.get(id);
            if (body == null) {
                problems.add("components: unknown child id");
                return null;
            }
            String type = body.keySet().iterator().next();
            Object propsRaw = body.get(type);
            Map<?, ?> props = propsRaw instanceof Map<?, ?> pm ? pm : Map.of();
            Map<String, Object> n = new LinkedHashMap<>();
            switch (type) {
                case "Text" -> {
                    String text = literal(props.get("text"), "Text.text");
                    String hint = props.get("usageHint") instanceof String h ? h : "";
                    if (hint.matches("h[1-5]")) {
                        n.put("_type", "heading");
                        n.put("level", hint.charAt(1) - '0');
                    } else {
                        n.put("_type", "text");
                    }
                    n.put("text", text);
                }
                case "Column", "Row", "List" -> {
                    n.put("_type", "container");
                    if ("Row".equals(type)) {
                        n.put("row", true);
                    }
                    n.put("children", children(props.get("children"), path, depth));
                }
                case "Card" -> {
                    n.put("_type", "card");
                    List<Object> kids = new ArrayList<>();
                    if (props.get("child") instanceof String c) {
                        kids.add(node(c, path, depth + 1));
                    } else {
                        problems.add("Card: child must name a component id");
                    }
                    n.put("children", kids);
                }
                case "Button" -> problems.add("Button: not supported yet: an A2UI action is sent back to an agent and needs a binding to an authorised Wayang operation");
                default -> problems.add("'" + type + "' components are not supported yet");
            }
            return n.isEmpty() ? null : n;
        } finally {
            path.remove(id);
        }
    }

    private List<Object> children(Object raw, Set<String> path, int depth) {
        List<Object> out = new ArrayList<>();
        if (raw instanceof Map<?, ?> m && m.get("explicitList") instanceof List<?> ids) {
            for (Object id : ids) {
                if (id instanceof String s) {
                    out.add(node(s, path, depth + 1));
                } else {
                    problems.add("children: ids must be text");
                }
            }
        } else {
            problems.add("children: only an explicitList is supported (no templates)");
        }
        return out;
    }

    private String literal(Object bound, String where) {
        if (bound instanceof Map<?, ?> m) {
            if (m.containsKey("path")) {
                problems.add(where + ": data-model bindings are not supported yet; use literalString");
            } else if (m.get("literalString") instanceof String s) {
                return s;
            } else {
                problems.add(where + ": needs a literalString");
            }
        } else {
            problems.add(where + ": needs a literalString");
        }
        return "";
    }
}
