package tech.kayys.wayang.api;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Read model of a process instance. {@code executedNodes} lists nodes that did
 * real work (were on a taken path), in order, so the Studio can highlight the
 * path on the canvas; {@code waiting} lists what the run is parked on.
 */
public record RunView(String id, String tenantId, String processId, int version, RunStatus status,
        Map<String, Object> variables, List<String> executedNodes, List<TaskView> waiting, String error,
        String startedBy, Instant startedAt, Instant finishedAt) {
    public RunView {
        variables = java.util.Collections.unmodifiableMap(variables);
        executedNodes = List.copyOf(executedNodes);
        waiting = List.copyOf(waiting);
    }
}
