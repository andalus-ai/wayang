package tech.kayys.wayang.bpmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.expression.Expressions;
import tech.kayys.wayang.expression.Scope;

/**
 * Run-time token logic for dead-path elimination. Stateless: everything is
 * derived from the workflow variables, which (in Gamelan) hold each completed
 * node's outputs under {@code <nodeId>.<key>}.
 *
 * <p>
 * Protocol for every node, whatever its executor:
 * <ol>
 * <li>{@link #isActive(PlanNode, Map)} - if false, skip the work and publish
 * {@link #deadOutputs(PlanNode)};</li>
 * <li>otherwise do the work, then publish {@link #liveOutputs(PlanNode, Map, Map)}
 * (which adds {@code flow.<flowId>} booleans to the node's own outputs).</li>
 * </ol>
 */
public final class FlowRuntime {

    private FlowRuntime() {
    }

    public static String flowKey(String flowId) {
        return "flow." + flowId;
    }

    /** Raised when a process cannot continue (e.g. exclusive gateway with no matching flow). */
    public static final class ProcessFlowException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public ProcessFlowException(String m) {
            super(m);
        }
    }

    /** Whether the node sits on a path that was actually taken. */
    public static boolean isActive(PlanNode node, Map<String, ?> vars) {
        if (node.incoming().isEmpty()) {
            return true; // start event
        }
        int active = 0;
        for (PlanNode.Incoming in : node.incoming()) {
            if (Boolean.TRUE.equals(vars.get(in.sourceId() + "." + flowKey(in.flowId())))) {
                active++;
            }
        }
        // Parallel join waits for every branch to be alive; every other node merges (any token suffices).
        return node.type() == ElementType.PARALLEL_GATEWAY ? active == node.incoming().size() : active > 0;
    }

    /** Outputs of a node that was skipped: all outgoing flows are dead. */
    public static Map<String, Object> deadOutputs(PlanNode node) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("active", false);
        for (PlanNode.Outgoing o : node.outgoing()) {
            out.put(flowKey(o.flowId()), false);
        }
        return out;
    }

    /**
     * Outputs of an executed node: {@code taskOutputs} plus the outgoing flow
     * flags. Conditions are evaluated against the nested workflow variables.
     */
    public static Map<String, Object> liveOutputs(PlanNode node, Map<String, ?> vars, Map<String, ?> taskOutputs) {
        Map<String, Object> out = new LinkedHashMap<>();
        if (taskOutputs != null) {
            out.putAll(taskOutputs);
        }
        out.put("active", true);
        Scope scope = scopeOf(vars);
        if (node.type() == ElementType.EXCLUSIVE_GATEWAY && node.outgoing().size() > 1) {
            String chosen = null;
            String dflt = null;
            for (PlanNode.Outgoing o : node.outgoing()) {
                if (o.isDefault()) {
                    dflt = o.flowId();
                } else if (chosen == null && holds(o, scope)) {
                    chosen = o.flowId();
                }
            }
            if (chosen == null) {
                chosen = dflt;
            }
            if (chosen == null) {
                throw new ProcessFlowException("Exclusive gateway '" + node.id() + "': no condition matched and there is no default flow");
            }
            for (PlanNode.Outgoing o : node.outgoing()) {
                out.put(flowKey(o.flowId()), o.flowId().equals(chosen));
            }
            return out;
        }
        boolean any = false;
        List<PlanNode.Outgoing> defaults = new ArrayList<>();
        for (PlanNode.Outgoing o : node.outgoing()) {
            if (o.isDefault()) {
                defaults.add(o);
                continue;
            }
            boolean on = holds(o, scope);
            any |= on;
            out.put(flowKey(o.flowId()), on);
        }
        for (PlanNode.Outgoing o : defaults) {
            out.put(flowKey(o.flowId()), !any);
            any = true;
        }
        if (node.type() == ElementType.INCLUSIVE_GATEWAY && !any && !node.outgoing().isEmpty()) {
            throw new ProcessFlowException("Inclusive gateway '" + node.id() + "': no condition matched and there is no default flow");
        }
        return out;
    }

    private static boolean holds(PlanNode.Outgoing o, Scope scope) {
        return o.condition() == null || Expressions.parse(o.condition()).test(scope);
    }

    /**
     * Turns flat workflow variables ({@code a.b.c = 1}) into nested maps
     * ({@code a: {b: {c: 1}}}) so expressions can write {@code a.b.c}. A plain
     * variable keeps precedence over a dotted key that would clash with it.
     */
    public static Scope scopeOf(Map<String, ?> vars) {
        return Scope.of(nest(vars));
    }

    /** The nested view used by {@link #scopeOf}; handy when a collaborator wants a plain Map. */
    @SuppressWarnings("unchecked")
    public static Map<String, Object> nest(Map<String, ?> vars) {
        Map<String, Object> root = new LinkedHashMap<>();
        // plain keys first so that they win clashes
        for (Map.Entry<String, ?> e : vars.entrySet()) {
            if (e.getKey().indexOf('.') < 0) {
                root.put(e.getKey(), e.getValue());
            }
        }
        for (Map.Entry<String, ?> e : vars.entrySet()) {
            String k = e.getKey();
            if (k.indexOf('.') < 0) {
                continue;
            }
            String[] parts = k.split("\\.");
            Map<String, Object> cur = root;
            boolean ok = true;
            for (int i = 0; i < parts.length - 1; i++) {
                Object next = cur.get(parts[i]);
                if (next == null) {
                    next = new LinkedHashMap<String, Object>();
                    cur.put(parts[i], next);
                } else if (next instanceof Map<?, ?> m && !(next instanceof LinkedHashMap)) {
                    next = new LinkedHashMap<String, Object>((Map<String, Object>) m);
                    cur.put(parts[i], next);
                }
                if (!(next instanceof Map)) {
                    ok = false;
                    break;
                }
                cur = (Map<String, Object>) next;
            }
            if (ok) {
                cur.putIfAbsent(parts[parts.length - 1], e.getValue());
            }
        }
        return root;
    }
}
