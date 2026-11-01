package tech.kayys.wayang.bpmn;

import java.util.List;
import java.util.Optional;

/** Engine-neutral compiled form of a process: an acyclic list of nodes in topological order. */
public record ExecutionPlan(String processId, String processName, List<PlanNode> nodes) {
    public ExecutionPlan {
        nodes = List.copyOf(nodes);
    }

    public Optional<PlanNode> node(String id) {
        return nodes.stream().filter(n -> n.id().equals(id)).findFirst();
    }
}
