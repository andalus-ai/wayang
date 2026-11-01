package tech.kayys.wayang.bpmn;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tech.kayys.wayang.expression.ExpressionException;
import tech.kayys.wayang.expression.Expressions;

/**
 * Immutable business-process definition (BPMN subset). Construct with
 * {@link #builder(String)} and call {@link #validate()}; {@link ProcessCompiler}
 * refuses models with errors.
 *
 * <p>
 * Supported: start/end events, exclusive/parallel/inclusive gateways, service,
 * business-rule, agent, EIP and user tasks, timer and message-catch events.
 * Not supported (rejected by validation, never silently ignored): cycles,
 * sub-processes, boundary events, multi-instance activities.
 */
public final class ProcessModel {

    /** Ids end up in variable names (<code>node.flow.id</code>), so dots are forbidden. */
    static final Pattern SAFE_ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    private final String id;
    private final String name;
    private final Map<String, Element> elements;
    private final List<SequenceFlow> flows;

    private ProcessModel(Builder b) {
        this.id = b.id;
        this.name = b.name == null ? b.id : b.name;
        this.elements = java.util.Collections.unmodifiableMap(new LinkedHashMap<>(b.elements));
        this.flows = List.copyOf(b.flows);
    }

    public String id() {
        return id;
    }

    public String name() {
        return name;
    }

    public Map<String, Element> elements() {
        return elements;
    }

    public List<SequenceFlow> flows() {
        return flows;
    }

    public List<SequenceFlow> outgoing(String elementId) {
        return flows.stream().filter(f -> f.source().equals(elementId)).toList();
    }

    public List<SequenceFlow> incoming(String elementId) {
        return flows.stream().filter(f -> f.target().equals(elementId)).toList();
    }

    public List<Issue> validate() {
        List<Issue> out = new ArrayList<>();
        if (id == null || !SAFE_ID.matcher(id).matches()) {
            out.add(error(null, "Process id must match " + SAFE_ID.pattern()));
        }
        for (Element e : elements.values()) {
            if (!SAFE_ID.matcher(e.id()).matches()) {
                out.add(error(e.id(), "Element id must match " + SAFE_ID.pattern() + " (no dots or spaces)"));
            }
        }
        Set<String> flowIds = new LinkedHashSet<>();
        for (SequenceFlow f : flows) {
            if (!SAFE_ID.matcher(f.id()).matches()) {
                out.add(error(f.id(), "Flow id must match " + SAFE_ID.pattern()));
            }
            if (!flowIds.add(f.id())) {
                out.add(error(f.id(), "Duplicate flow id"));
            }
            if (!elements.containsKey(f.source())) {
                out.add(error(f.id(), "Flow source '" + f.source() + "' does not exist"));
            }
            if (!elements.containsKey(f.target())) {
                out.add(error(f.id(), "Flow target '" + f.target() + "' does not exist"));
            }
            if (f.condition() != null) {
                try {
                    Expressions.parse(f.condition());
                } catch (ExpressionException ex) {
                    out.add(error(f.id(), "Invalid condition '" + f.condition() + "': " + ex.getMessage()));
                }
            }
        }
        long starts = elements.values().stream().filter(e -> e.type() == ElementType.START_EVENT).count();
        long ends = elements.values().stream().filter(e -> e.type() == ElementType.END_EVENT).count();
        if (starts != 1) {
            out.add(error(null, "A process needs exactly one start event but has " + starts));
        }
        if (ends < 1) {
            out.add(error(null, "A process needs at least one end event"));
        }
        for (Element e : elements.values()) {
            List<SequenceFlow> in = incoming(e.id());
            List<SequenceFlow> outs = outgoing(e.id());
            switch (e.type()) {
                case START_EVENT -> {
                    if (!in.isEmpty()) {
                        out.add(error(e.id(), "Start event must not have incoming flows"));
                    }
                    if (outs.isEmpty()) {
                        out.add(error(e.id(), "Start event needs an outgoing flow"));
                    }
                }
                case END_EVENT -> {
                    if (!outs.isEmpty()) {
                        out.add(error(e.id(), "End event must not have outgoing flows"));
                    }
                    if (in.isEmpty()) {
                        out.add(error(e.id(), "End event needs an incoming flow"));
                    }
                }
                default -> {
                    if (in.isEmpty()) {
                        out.add(error(e.id(), "Element is unreachable (no incoming flow)"));
                    }
                    if (outs.isEmpty()) {
                        out.add(error(e.id(), "Element has no outgoing flow; connect it to an end event"));
                    }
                }
            }
            NodePolicy.problems(e.id(), e.type(), e.config()).forEach(m -> out.add(error(e.id(), m)));
            if (e.type() == ElementType.TIMER_EVENT) {
                Object d = e.config().get("duration");
                Object u = e.config().get("until");
                if ((d == null) == (u == null)) {
                    out.add(error(e.id(), "Timer needs exactly one of 'duration' (e.g. PT30M) or 'until' (ISO date-time)"));
                } else {
                    try {
                        if (d != null) {
                            java.time.Duration.parse(String.valueOf(d));
                        } else {
                            java.time.OffsetDateTime.parse(String.valueOf(u));
                        }
                    } catch (java.time.format.DateTimeParseException ex) {
                        out.add(error(e.id(), "Invalid timer value: " + ex.getMessage()));
                    }
                }
            }
            if (e.type() == ElementType.MESSAGE_CATCH_EVENT) {
                if (!(e.config().get("message") instanceof String m) || m.isBlank()) {
                    out.add(error(e.id(), "Message event needs a 'message' name"));
                }
                if (e.config().get("correlation") instanceof String c) {
                    try {
                        Expressions.parse(c);
                    } catch (ExpressionException ex) {
                        out.add(error(e.id(), "Invalid correlation expression: " + ex.getMessage()));
                    }
                }
            }
            if (e.type() == ElementType.PARALLEL_GATEWAY) {
                for (SequenceFlow f : outs) {
                    if (f.condition() != null || f.isDefault()) {
                        out.add(error(f.id(), "Flows leaving a parallel gateway cannot have conditions or be default"));
                    }
                }
            }
            if (e.type() == ElementType.EXCLUSIVE_GATEWAY || e.type() == ElementType.INCLUSIVE_GATEWAY) {
                long defaults = outs.stream().filter(SequenceFlow::isDefault).count();
                if (defaults > 1) {
                    out.add(error(e.id(), "A gateway can have at most one default flow"));
                }
                if (outs.size() > 1) {
                    for (SequenceFlow f : outs) {
                        if (!f.isDefault() && f.condition() == null) {
                            out.add(error(f.id(), "Flow leaving a splitting " + e.type()
                                    + " needs a condition or must be the default"));
                        }
                    }
                    if (defaults == 0) {
                        out.add(new Issue(Issue.Severity.WARNING, e.id(),
                                "No default flow: the process fails at runtime if no condition matches"));
                    }
                }
            } else if (!e.type().isGateway()) {
                for (SequenceFlow f : outs) {
                    if (f.isDefault()) {
                        out.add(error(f.id(), "Only gateways can have default flows"));
                    }
                }
            }
        }
        cycleCheck(out);
        reachability(out);
        return out;
    }

    public boolean isValid() {
        return validate().stream().noneMatch(i -> i.severity() == Issue.Severity.ERROR);
    }

    private void cycleCheck(List<Issue> out) {
        Set<String> done = new LinkedHashSet<>();
        Set<String> stack = new LinkedHashSet<>();
        for (String s : elements.keySet()) {
            if (dfs(s, done, stack, out)) {
                return;
            }
        }
    }

    private boolean dfs(String n, Set<String> done, Set<String> stack, List<Issue> out) {
        if (done.contains(n)) {
            return false;
        }
        if (!stack.add(n)) {
            out.add(error(n, "Cycle detected (" + String.join(" -> ", stack) + " -> " + n + "). Loops are not supported "
                    + "because workflows run as acyclic graphs; model repetition with an EIP splitter or a retry policy"));
            return true;
        }
        for (SequenceFlow f : outgoing(n)) {
            if (elements.containsKey(f.target()) && dfs(f.target(), done, stack, out)) {
                return true;
            }
        }
        stack.remove(n);
        done.add(n);
        return false;
    }

    private void reachability(List<Issue> out) {
        String start = elements.values().stream().filter(e -> e.type() == ElementType.START_EVENT)
                .map(Element::id).findFirst().orElse(null);
        if (start == null) {
            return;
        }
        Set<String> seen = new LinkedHashSet<>();
        java.util.ArrayDeque<String> q = new java.util.ArrayDeque<>();
        q.add(start);
        while (!q.isEmpty()) {
            String n = q.poll();
            if (seen.add(n)) {
                for (SequenceFlow f : outgoing(n)) {
                    q.add(f.target());
                }
            }
        }
        for (String e : elements.keySet()) {
            if (!seen.contains(e)) {
                out.add(error(e, "Element is not reachable from the start event"));
            }
        }
    }

    private static Issue error(String element, String msg) {
        return new Issue(Issue.Severity.ERROR, element, msg);
    }

    public static Builder builder(String id) {
        return new Builder(id);
    }

    public static final class Builder {
        private final String id;
        private String name;
        private final Map<String, Element> elements = new LinkedHashMap<>();
        private final List<SequenceFlow> flows = new ArrayList<>();

        private Builder(String id) {
            this.id = id;
        }

        public Builder name(String n) {
            this.name = n;
            return this;
        }

        public Builder element(String id, ElementType type, String name, Map<String, Object> config) {
            if (elements.putIfAbsent(id, new Element(id, name, type, config)) != null) {
                throw new IllegalArgumentException("Duplicate element id '" + id + "'");
            }
            return this;
        }

        public Builder start(String id) {
            return element(id, ElementType.START_EVENT, id, null);
        }

        public Builder end(String id) {
            return element(id, ElementType.END_EVENT, id, null);
        }

        public Builder task(String id, ElementType type, Map<String, Object> config) {
            return element(id, type, id, config);
        }

        public Builder exclusive(String id) {
            return element(id, ElementType.EXCLUSIVE_GATEWAY, id, null);
        }

        public Builder parallel(String id) {
            return element(id, ElementType.PARALLEL_GATEWAY, id, null);
        }

        public Builder inclusive(String id) {
            return element(id, ElementType.INCLUSIVE_GATEWAY, id, null);
        }

        public Builder flow(String from, String to) {
            return flow(from, to, null, false);
        }

        public Builder flow(String from, String to, String condition) {
            return flow(from, to, condition, false);
        }

        public Builder defaultFlow(String from, String to) {
            return flow(from, to, null, true);
        }

        private Builder flow(String from, String to, String condition, boolean dflt) {
            String fid = "f" + (flows.size() + 1);
            flows.add(new SequenceFlow(fid, from, to, condition, dflt));
            return this;
        }

        public Builder flow(String flowId, String from, String to, String condition, boolean dflt) {
            flows.add(new SequenceFlow(flowId, from, to, condition, dflt));
            return this;
        }

        public ProcessModel build() {
            return new ProcessModel(this);
        }
    }
}
