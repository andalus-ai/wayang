package tech.kayys.wayang.adapter.gamelan;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.PlanNode;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.dmn.DecisionTable;
import tech.kayys.wayang.runtime.ControlHandler;
import tech.kayys.wayang.runtime.DmnHandler;
import tech.kayys.wayang.runtime.NodeOutcome;
import tech.kayys.wayang.runtime.NodeRunner;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;

/**
 * Drives Gamelan's real {@link WorkflowRun} scheduler (not a re-implementation)
 * with Wayang's node logic, to check that dead-path elimination terminates and
 * routes correctly under the scheduler's actual dependency semantics.
 * Not a test of the executors' Mutiny plumbing or of the network transport.
 */
class GamelanSchedulerConformanceTest {

    private static ProcessModel loan() {
        return ProcessModel.builder("loan").start("s")
                .task("assess", ElementType.BUSINESS_RULE_TASK, Map.of("model", "risk"))
                .exclusive("g")
                .task("review", ElementType.SERVICE_TASK, Map.of("connector", "manual"))
                .task("grant", ElementType.SERVICE_TASK, Map.of("connector", "ledger"))
                .exclusive("m").end("e")
                .flow("s", "assess").flow("assess", "g")
                .flow("g", "review", "assess.result = \"high\"").defaultFlow("g", "grant")
                .flow("review", "m").flow("grant", "m").flow("m", "e").build();
    }

    private static NodeRunner runner(List<String> calls) {
        DecisionModel risk = DecisionModel.builder("risk")
                .table(DecisionTable.builder("risk").input("applicant.age").output("risk")
                        .rule(List.of("< 25"), List.of("\"high\"")).rule(List.of(">= 25"), List.of("\"low\"")).build())
                .build();
        return new NodeRunner(List.of(new ControlHandler(new WaitRegistry.InMemory()), new DmnHandler(Map.of("risk", risk)),
                new ServiceHandler(Map.of(
                        "manual", in -> {
                            calls.add("manual");
                            return Map.of("decision", "approved");
                        },
                        "ledger", in -> {
                            calls.add("ledger");
                            return Map.of("ok", true);
                        }))));
    }

    private static WorkflowRun drive(Map<String, Object> inputs, List<String> calls, List<String> order) {
        WorkflowDefinition def = GamelanPlanMapper.toDefinition(ProcessCompiler.compile(loan()), "t1", "1");
        WorkflowRun run = WorkflowRun.create(TenantId.of("t1"), def, inputs);
        run.start();
        NodeRunner r = runner(calls);
        for (int guard = 0; guard < 100 && run.status() == RunStatus.RUNNING; guard++) {
            boolean progressed = false;
            for (NodeDefinition nd : def.nodes()) {
                var exec = safeExec(run, nd);
                if (exec == null || exec.getStatus() != NodeExecutionStatus.PENDING) {
                    continue;
                }
                int attempt = exec.getAttempt();
                run.startNode(nd.id(), attempt);
                PlanNode pn = PlanNode.fromConfiguration(nd.configuration());
                Map<String, Object> vars = new HashMap<>(run.getContext().getVariables());
                NodeOutcome o = r.run(run.id().value(), pn, vars);
                assertTrue(o instanceof NodeOutcome.Completed, "unexpected outcome " + o);
                order.add(nd.id().value());
                run.completeNode(nd.id(), attempt, ((NodeOutcome.Completed) o).outputs());
                progressed = true;
            }
            assertTrue(progressed, "scheduler made no progress, status=" + run.status());
        }
        return run;
    }

    private static tech.kayys.gamelan.engine.node.NodeExecution safeExec(WorkflowRun run, NodeDefinition nd) {
        try {
            return run.getNodeExecution(nd.id());
        } catch (RuntimeException e) {
            return null;
        }
    }

    @Test
    void definitionIsAValidDag() {
        WorkflowDefinition def = GamelanPlanMapper.toDefinition(ProcessCompiler.compile(loan()), "t1", "1");
        assertEquals(7, def.nodes().size());
        assertEquals("wayang-dmn", def.nodes().stream().filter(n -> n.id().value().equals("assess")).findFirst().get().executorType());
    }

    @Test
    void lowRiskRunCompletesAndSkipsReview() {
        var calls = new ArrayList<String>();
        var order = new ArrayList<String>();
        WorkflowRun run = drive(Map.of("applicant", Map.of("age", 40)), calls, order);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(List.of("ledger"), calls);
        assertEquals(7, order.size(), "every node is scheduled, dead ones no-op");
        assertEquals(Boolean.FALSE, run.getContext().getVariables().get("review.active"));
        assertEquals(Boolean.TRUE, run.getContext().getVariables().get("grant.active"));
        assertEquals(Boolean.TRUE, run.getContext().getVariables().get("e.active"));
    }

    @Test
    void highRiskRunTakesReviewBranch() {
        var calls = new ArrayList<String>();
        var order = new ArrayList<String>();
        WorkflowRun run = drive(Map.of("applicant", Map.of("age", 20)), calls, order);
        assertEquals(RunStatus.COMPLETED, run.status());
        assertEquals(List.of("manual"), calls);
        assertEquals(Boolean.FALSE, run.getContext().getVariables().get("grant.active"));
        assertEquals(Boolean.TRUE, run.getContext().getVariables().get("e.active"));
    }
}
