package tech.kayys.wayang.adapter.gamelan;

import java.util.ArrayList;
import java.util.List;

import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeId;
import tech.kayys.gamelan.engine.node.NodeType;
import tech.kayys.gamelan.engine.run.RetryPolicy;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinitionId;
import tech.kayys.gamelan.engine.workflow.WorkflowMode;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.bpmn.NodePolicy;
import tech.kayys.wayang.bpmn.PlanNode;

/**
 * Maps a Wayang {@link ExecutionPlan} to a Gamelan {@link WorkflowDefinition}.
 *
 * <ul>
 * <li>Every Wayang node becomes one Gamelan DAG node of type {@code TASK};
 * the Gamelan {@code executorType} selects the Wayang executor. Special node
 * types (HUMAN_TASK, TIMER, EVENT_WAIT) are deliberately <em>not</em> used,
 * because their engine-side semantics were not verified.</li>
 * <li>The complete {@link PlanNode} (flows, conditions, config) travels in the
 * node configuration, so executors are stateless.</li>
 * <li>Gamelan's scheduler is dependency-only; branching is realised by
 * dead-path elimination inside the executors (see wayang-business-process).</li>
 * <li>{@link NodePolicy}: {@code timeout} maps to the node timeout and {@code retries}/{@code retryDelay}/
 * {@code retryMaxDelay}/{@code retryBackoff} to Gamelan's {@code RetryPolicy}. Nodes are always
 * {@code critical}: Gamelan never schedules the dependents of a failed non-critical node (the run would
 * end as WORKFLOW_STUCK), so "continue on error" is done by the executors ({@code onError: continue}).</li>
 * </ul>
 */
public final class GamelanPlanMapper {

    private GamelanPlanMapper() {
    }

    public static WorkflowDefinition toDefinition(ExecutionPlan plan, String tenantId, String version) {
        List<NodeDefinition> nodes = new ArrayList<>();
        for (PlanNode n : plan.nodes()) {
            List<NodeId> deps = n.dependsOn().stream().map(NodeId::of).toList();
            NodeDefinition.Builder b = NodeDefinition.builder()
                    .id(NodeId.of(n.id()))
                    .name(n.name())
                    .type(NodeType.TASK)
                    .executorType(n.executorType())
                    .configuration(n.toConfiguration())
                    .dependsOn(deps)
                    .critical(true);
            NodePolicy policy = NodePolicy.of(n);
            if (policy.timeout() != null) {
                b.timeout(policy.timeout());
            }
            if (policy.maxAttempts() > 1) {
                b.retryPolicy(new RetryPolicy(policy.maxAttempts(), policy.retryDelay(), policy.retryMaxDelay(),
                        policy.retryBackoff(), List.of()));
            }
            nodes.add(b.build());
        }
        return WorkflowDefinition.builder()
                .id(WorkflowDefinitionId.of(plan.processId()))
                .tenantId(TenantId.of(tenantId))
                .name(plan.processName())
                .version(version)
                .mode(WorkflowMode.DAG)
                .nodes(nodes)
                .buildAndValidate();
    }
}
