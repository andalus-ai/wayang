package tech.kayys.wayang.bpmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Wayang's native JSON/Map form of a process, which is what the Studio edits:
 *
 * <pre>
 * { "id": "loan", "name": "Loan",
 *   "elements": [ {"id": "s", "type": "START_EVENT"},
 *                 {"id": "t", "type": "SERVICE_TASK", "name": "Call", "config": {"connector": "x"}} ],
 *   "flows":    [ {"id": "f1", "source": "s", "target": "t", "condition": "x &gt; 1", "default": false} ] }
 * </pre>
 *
 * Unknown keys are rejected so typos are not silently dropped.
 */
public final class ProcessMaps {

    private static final Set<String> TOP = Set.of("id", "name", "elements", "flows");
    private static final Set<String> EL = Set.of("id", "type", "name", "config");
    private static final Set<String> FLOW = Set.of("id", "source", "target", "condition", "default");
    private static final int MAX_ELEMENTS = 2000;
    private static final int MAX_FLOWS = 6000;

    private ProcessMaps() {
    }

    /** @throws IllegalArgumentException with a precise message when the document is malformed */
    public static ProcessModel fromMap(Map<String, ?> doc) {
        unknownKeys("process", doc, TOP);
        Object id = doc.get("id");
        if (!(id instanceof String pid) || pid.isBlank()) {
            throw new IllegalArgumentException("process.id is required");
        }
        ProcessModel.Builder b = ProcessModel.builder(pid);
        if (doc.get("name") instanceof String n && !n.isBlank()) {
            b.name(n);
        }
        List<?> els = list(doc, "elements", MAX_ELEMENTS);
        List<?> flows = list(doc, "flows", MAX_FLOWS);
        int i = 0;
        for (Object o : els) {
            Map<?, ?> m = obj(o, "elements[" + i + "]");
            unknownKeys("elements[" + i + "]", m, EL);
            String eid = str(m, "id", "elements[" + i + "]");
            ElementType t;
            try {
                t = ElementType.valueOf(str(m, "type", "element " + eid).toUpperCase(java.util.Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException("element " + eid + ": unknown type '" + m.get("type") + "'");
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            if (m.get("config") != null) {
                if (!(m.get("config") instanceof Map<?, ?> cm)) {
                    throw new IllegalArgumentException("element " + eid + ": config must be an object");
                }
                cm.forEach((k, v) -> cfg.put(String.valueOf(k), v));
            }
            try {
                b.element(eid, t, m.get("name") instanceof String nm ? nm : eid, cfg);
            } catch (IllegalArgumentException e) {
                throw new IllegalArgumentException(e.getMessage());
            }
            i++;
        }
        i = 0;
        for (Object o : flows) {
            Map<?, ?> m = obj(o, "flows[" + i + "]");
            unknownKeys("flows[" + i + "]", m, FLOW);
            String fid = str(m, "id", "flows[" + i + "]");
            Object cond = m.get("condition");
            b.flow(fid, str(m, "source", "flow " + fid), str(m, "target", "flow " + fid),
                    cond == null || String.valueOf(cond).isBlank() ? null : String.valueOf(cond),
                    Boolean.TRUE.equals(m.get("default")));
            i++;
        }
        return b.build();
    }

    public static Map<String, Object> toMap(ProcessModel m) {
        Map<String, Object> doc = new LinkedHashMap<>();
        doc.put("id", m.id());
        doc.put("name", m.name());
        List<Object> els = new ArrayList<>();
        for (Element e : m.elements().values()) {
            Map<String, Object> em = new LinkedHashMap<>();
            em.put("id", e.id());
            em.put("type", e.type().name());
            em.put("name", e.name());
            em.put("config", new LinkedHashMap<>(e.config()));
            els.add(em);
        }
        doc.put("elements", els);
        List<Object> fl = new ArrayList<>();
        for (SequenceFlow f : m.flows()) {
            Map<String, Object> fm = new LinkedHashMap<>();
            fm.put("id", f.id());
            fm.put("source", f.source());
            fm.put("target", f.target());
            if (f.condition() != null) {
                fm.put("condition", f.condition());
            }
            fm.put("default", f.isDefault());
            fl.add(fm);
        }
        doc.put("flows", fl);
        return doc;
    }

    private static void unknownKeys(String where, Map<?, ?> m, Set<String> allowed) {
        for (Object k : m.keySet()) {
            if (!allowed.contains(String.valueOf(k))) {
                throw new IllegalArgumentException(where + ": unknown field '" + k + "'");
            }
        }
    }

    private static List<?> list(Map<String, ?> m, String key, int max) {
        Object v = m.get(key);
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw new IllegalArgumentException("process." + key + " must be a list");
        }
        if (l.size() > max) {
            throw new IllegalArgumentException("process." + key + " has " + l.size() + " entries, the limit is " + max);
        }
        return l;
    }

    private static Map<?, ?> obj(Object o, String where) {
        if (o instanceof Map<?, ?> m) {
            return m;
        }
        throw new IllegalArgumentException(where + " must be an object");
    }

    private static String str(Map<?, ?> m, String key, String where) {
        Object v = m.get(key);
        if (!(v instanceof String s) || s.isBlank()) {
            throw new IllegalArgumentException(where + ": '" + key + "' is required");
        }
        return s;
    }
}
