package tech.kayys.wayang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tech.kayys.wayang.agent.PromptTemplate;
import tech.kayys.wayang.bpmn.FlowRuntime;
import tech.kayys.wayang.bpmn.PlanNode;

/**
 * Executor {@code wayang-human}: creates a task for a person and parks.
 * Config: {@code title}, {@code assignee}, {@code form} (opaque form
 * definition); string values may contain {@code {{ expression }}}.
 */
public final class HumanHandler implements DomainHandler {

    private final WaitRegistry waits;

    public HumanHandler(WaitRegistry waits) {
        this.waits = waits;
    }

    @Override
    public String executorType() {
        return "wayang-human";
    }

    @Override
    public NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars) {
        Map<String, Object> info = new LinkedHashMap<>();
        var scope = FlowRuntime.scopeOf(vars);
        node.config().forEach((k, v) -> info.put(k, v instanceof String s ? PromptTemplate.render(s, scope) : v));
        info.put("nodeId", node.id());
        WaitTicket ticket = new WaitTicket(UUID.randomUUID().toString(), node.id(), "human", info);
        // register() is idempotent per run+node: a re-dispatched node re-attaches to its existing task.
        return new NodeOutcome.Waiting(waits.register(runId, ticket));
    }
}
