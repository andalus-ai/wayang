package tech.kayys.wayang.adapter.gamelan;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import tech.kayys.gamelan.engine.error.ErrorInfo;
import tech.kayys.gamelan.engine.node.NodeDefinition;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.run.RunStatus;
import tech.kayys.gamelan.engine.tenant.TenantId;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.engine.workflow.WorkflowRun;
import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.PlanNode;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.runtime.NodeOutcome;
import tech.kayys.wayang.runtime.NodeRunner;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Checks, against Gamelan's real {@link WorkflowRun}, that Wayang's retry/error
 * policy maps onto the engine's retry machinery and that "continue on error"
 * works despite the engine never scheduling dependents of a failed node.
 */
class GamelanRetryConformanceTest {

    private static ProcessModel process(Map<String, Object> cfg) {
        return ProcessModel.builder("p").start("s").task("call", ElementType.SERVICE_TASK, cfg)
                .exclusive("g").task("fallback", ElementType.SERVICE_TASK, Map.of("connector", "ok"))
                .task("normal", ElementType.SERVICE_TASK, Map.of("connector", "ok")).exclusive("m").end("e")
                .flow("s", "call").flow("call", "g")
                .flow("g", "fallback", "call.failed = true").defaultFlow("g", "normal")
                .flow("fallback", "m").flow("normal", "m").flow("m", "e").build();
    }

    private record Result(WorkflowRun run, int calls) {
    }

    /** @param failFirst how many calls of connector "flaky" fail before it starts to succeed */
    private static Result drive(Map<String, Object> cfg, int failFirst) {
        AtomicInteger calls = new AtomicInteger();
        NodeRunner runner = new NodeRunner(List.of(new tech.kayys.wayang.runtime.ControlHandler(
                new tech.kayys.wayang.runtime.WaitRegistry.InMemory()), new ServiceHandler(Map.of(
                        "flaky", in -> {
                            if (calls.incrementAndGet() <= failFirst) {
                                throw new IllegalStateException("backend down");
                            }
                            return Map.of("ok", true);
                        },
                        "ok", in -> Map.of("fine", true)))));
        WorkflowDefinition def = GamelanPlanMapper.toDefinition(ProcessCompiler.compile(process(cfg)), "t1", "1");
        WorkflowRun run = WorkflowRun.create(TenantId.of("t1"), def, Map.of());
        run.start();
        for (int guard = 0; guard < 200 && run.status() == RunStatus.RUNNING; guard++) {
            boolean progressed = false;
            for (NodeDefinition nd : def.nodes()) {
                tech.kayys.gamelan.engine.node.NodeExecution exec;
                try {
                    exec = run.getNodeExecution(nd.id());
                } catch (RuntimeException e) {
                    continue;
                }
                if (exec.getStatus() != NodeExecutionStatus.PENDING && !exec.canRetry()) {
                    continue;
                }
                int attempt = exec.getAttempt();
                run.startNode(nd.id(), attempt);
                PlanNode pn = PlanNode.fromConfiguration(nd.configuration());
                NodeOutcome o = runner.run(run.id().value(), pn, new HashMap<>(run.getContext().getVariables()), attempt);
                if (o instanceof NodeOutcome.Completed c) {
                    run.completeNode(nd.id(), attempt, c.outputs());
                } else {
                    run.failNode(nd.id(), attempt, new ErrorInfo("WAYANG_FAILED", ((NodeOutcome.Failed) o).error(), null, Map.of()));
                }
                progressed = true;
                if (run.status() != RunStatus.RUNNING) {
                    break;
                }
            }
            if (!progressed) {
                break;
            }
        }
        return new Result(run, calls.get());
    }

    @Test
    void retriesAreMappedToTheEnginePolicy() {
        WorkflowDefinition def = GamelanPlanMapper.toDefinition(ProcessCompiler.compile(process(
                Map.of("connector", "flaky", "retries", 2, "retryDelay", "PT3S", "retryBackoff", 3.0, "timeout", "PT20S"))), "t1", "1");
        NodeDefinition call = def.nodes().stream().filter(n -> n.id().value().equals("call")).findFirst().get();
        assertEquals(3, call.retryPolicy().maxAttempts());
        assertEquals(Duration.ofSeconds(3), call.retryPolicy().initialDelay());
        assertEquals(3.0, call.retryPolicy().backoffMultiplier(), 0.0001);
        assertEquals(Duration.ofSeconds(20), call.timeout());
        NodeDefinition fallback = def.nodes().stream().filter(n -> n.id().value().equals("fallback")).findFirst().get();
        assertEquals(1, fallback.retryPolicy().maxAttempts(), "no retries unless asked for");
    }

    @Test
    void transientFailureIsRetriedUntilItSucceeds() {
        Result r = drive(Map.of("connector", "flaky", "retries", 2, "retryDelay", "PT0S"), 2);
        assertEquals(RunStatus.COMPLETED, r.run().status());
        assertEquals(3, r.calls());
    }

    @Test
    void exhaustedRetriesFailTheRunByDefault() {
        Result r = drive(Map.of("connector", "flaky", "retries", 1, "retryDelay", "PT0S"), 99);
        assertEquals(RunStatus.FAILED, r.run().status());
        assertEquals(2, r.calls());
    }

    @Test
    void noRetriesMeansOneAttempt() {
        Result r = drive(Map.of("connector", "flaky"), 99);
        assertEquals(RunStatus.FAILED, r.run().status());
        assertEquals(1, r.calls());
    }

    @Test
    void continueOnErrorKeepsTheRunGoingAfterTheLastAttempt() {
        Result r = drive(Map.of("connector", "flaky", "retries", 1, "retryDelay", "PT0S", "onError", "continue"), 99);
        assertEquals(RunStatus.COMPLETED, r.run().status());
        assertEquals(2, r.calls(), "retried once, then converted into a completed node");
        Map<String, Object> vars = r.run().getContext().getVariables();
        assertEquals(Boolean.TRUE, vars.get("call.failed"));
        assertEquals(Boolean.TRUE, vars.get("fallback.active"));
        assertEquals(Boolean.FALSE, vars.get("normal.active"));
    }
}
