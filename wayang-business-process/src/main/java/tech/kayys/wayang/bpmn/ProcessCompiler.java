package tech.kayys.wayang.bpmn;

import java.util.ArrayList;
import java.util.List;
import java.util.stream.Collectors;

/**
 * Compiles a {@link ProcessModel} into an acyclic {@link ExecutionPlan}.
 *
 * <p>
 * The target engine (Gamelan) schedules a node once all its dependencies have
 * completed and does not evaluate branch conditions. Branching is therefore
 * compiled using <em>dead-path elimination</em>: every node is scheduled, and
 * {@link FlowRuntime} decides at run time whether it is on a taken path
 * ("active") or not (it then completes immediately without doing any work and
 * marks its outgoing flows dead).
 */
public final class ProcessCompiler {

    private ProcessCompiler() {
    }

    public static ExecutionPlan compile(ProcessModel model) {
        List<Issue> issues = model.validate();
        List<Issue> errors = issues.stream().filter(i -> i.severity() == Issue.Severity.ERROR).toList();
        if (!errors.isEmpty()) {
            throw new InvalidProcessException(errors);
        }
        List<PlanNode> nodes = new ArrayList<>();
        for (String id : topologicalOrder(model)) {
            Element e = model.elements().get(id);
            List<PlanNode.Incoming> in = model.incoming(id).stream()
                    .map(f -> new PlanNode.Incoming(f.id(), f.source())).toList();
            List<PlanNode.Outgoing> out = model.outgoing(id).stream()
                    .map(f -> new PlanNode.Outgoing(f.id(), f.target(), f.condition(), f.isDefault())).toList();
            String exec = e.config().get("executorType") instanceof String s ? s : e.type().defaultExecutorType();
            nodes.add(new PlanNode(id, e.name(), e.type(), exec, e.config(), in, out));
        }
        return new ExecutionPlan(model.id(), model.name(), nodes);
    }

    private static List<String> topologicalOrder(ProcessModel m) {
        List<String> order = new ArrayList<>();
        java.util.Map<String, Integer> indeg = new java.util.LinkedHashMap<>();
        m.elements().keySet().forEach(k -> indeg.put(k, 0));
        m.flows().forEach(f -> indeg.merge(f.target(), 1, Integer::sum));
        java.util.ArrayDeque<String> q = indeg.entrySet().stream().filter(e -> e.getValue() == 0)
                .map(java.util.Map.Entry::getKey).collect(Collectors.toCollection(java.util.ArrayDeque::new));
        while (!q.isEmpty()) {
            String n = q.poll();
            order.add(n);
            for (SequenceFlow f : m.outgoing(n)) {
                if (indeg.merge(f.target(), -1, Integer::sum) == 0) {
                    q.add(f.target());
                }
            }
        }
        return order;
    }

    /** Thrown when a model has validation errors. */
    public static final class InvalidProcessException extends RuntimeException {
        private static final long serialVersionUID = 1L;
        private final transient List<Issue> issues;

        public InvalidProcessException(List<Issue> issues) {
            super("Invalid process: " + issues.stream().map(Issue::toString).collect(Collectors.joining("; ")));
            this.issues = List.copyOf(issues);
        }

        public List<Issue> issues() {
            return issues;
        }
    }
}
