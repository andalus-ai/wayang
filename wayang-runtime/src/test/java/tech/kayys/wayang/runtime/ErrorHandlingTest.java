package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.NodePolicy;
import tech.kayys.wayang.bpmn.PlanNode;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;

class ErrorHandlingTest {

    private static ProcessModel process(Map<String, Object> taskCfg) {
        return ProcessModel.builder("p").start("s").task("call", ElementType.SERVICE_TASK, taskCfg)
                .exclusive("g").task("fallback", ElementType.SERVICE_TASK, Map.of("connector", "ok"))
                .task("normal", ElementType.SERVICE_TASK, Map.of("connector", "ok")).exclusive("m").end("e")
                .flow("s", "call").flow("call", "g")
                .flow("g", "fallback", "call.failed = true").defaultFlow("g", "normal")
                .flow("fallback", "m").flow("normal", "m").flow("m", "e").build();
    }

    private static ReferenceEngine engine() {
        Map<String, ServiceHandler.Connector> c = Map.of("boom", in -> {
            throw new IllegalStateException("backend down");
        }, "ok", in -> Map.of("fine", true));
        return new ReferenceEngine(new NodeRunner(List.of(new ControlHandler(new WaitRegistry.InMemory()), new ServiceHandler(c))),
                new WaitRegistry.InMemory());
    }

    @Test
    void failureFailsTheRunByDefault() {
        var run = engine().start(ProcessCompiler.compile(process(Map.of("connector", "boom"))), Map.of());
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertTrue(run.error().contains("backend down"));
    }

    @Test
    void onErrorContinueRoutesThroughTheFallback() {
        var run = engine().start(ProcessCompiler.compile(process(Map.of("connector", "boom", "onError", "continue"))), Map.of());
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals(true, run.variables().get("call.failed"));
        assertTrue(String.valueOf(run.variables().get("call.error")).contains("backend down"));
        assertTrue(run.executedNodes().contains("fallback"));
        assertFalse(run.executedNodes().contains("normal"));
    }

    @Test
    void successSetsFailedFalseSoConditionsWorkOnBothPaths() {
        var run = engine().start(ProcessCompiler.compile(process(Map.of("connector", "ok", "onError", "continue"))), Map.of());
        assertEquals(false, run.variables().get("call.failed"));
        assertTrue(run.executedNodes().contains("normal"));
        assertFalse(run.executedNodes().contains("fallback"));
    }

    @Test
    void continueOnlyAppliesOnTheLastAttempt() {
        var plan = ProcessCompiler.compile(process(Map.of("connector", "boom", "onError", "continue", "retries", 2)));
        PlanNode call = plan.node("call").get();
        Map<String, Object> live = Map.of("s.flow.f1", true); // the start event's flow into "call" is live
        NodeRunner runner = new NodeRunner(List.of(new ServiceHandler(Map.of("boom", in -> {
            throw new IllegalStateException("backend down");
        }))));
        assertTrue(runner.run("r", call, live, 1) instanceof NodeOutcome.Failed, "attempt 1 of 3 must reach the engine's retry");
        assertTrue(runner.run("r", call, live, 2) instanceof NodeOutcome.Failed);
        assertTrue(runner.run("r", call, live, 3) instanceof NodeOutcome.Completed, "last attempt converts into a completed node");
    }

    @Test
    void policyIsParsedWithDefaults() {
        var plan = ProcessCompiler.compile(process(Map.of("connector", "ok", "retries", 3, "retryDelay", "PT2S",
                "timeout", "PT30S", "retryBackoff", 1.5)));
        NodePolicy p = NodePolicy.of(plan.node("call").get());
        assertEquals(4, p.maxAttempts());
        assertEquals(Duration.ofSeconds(2), p.retryDelay());
        assertEquals(Duration.ofMinutes(5), p.retryMaxDelay());
        assertEquals(1.5, p.retryBackoff(), 0.0001);
        assertEquals(Duration.ofSeconds(30), p.timeout());
        assertFalse(p.continueOnError());
        assertEquals(1, NodePolicy.of(plan.node("fallback").get()).maxAttempts(), "retries are opt-in");
    }

    @Test
    void invalidPolicyIsRejectedAtCompileTime() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of("retries", 99), Map.of("retries", -1),
                Map.of("retries", "many"), Map.of("retryDelay", "soon"), Map.of("retryBackoff", 0.5),
                Map.of("onError", "ignore"), Map.of("timeout", "PT0S"),
                Map.of("retryDelay", "PT10S", "retryMaxDelay", "PT1S"))) {
            assertThrows(ProcessCompiler.InvalidProcessException.class, () -> ProcessCompiler.compile(process(bad)), bad.toString());
        }
    }

    @Test
    void retriesAreRefusedWhereTheyMakeNoSense() {
        var m = ProcessModel.builder("p").start("s").task("ask", ElementType.USER_TASK, Map.of("retries", 2)).end("e")
                .flow("s", "ask").flow("ask", "e").build();
        assertThrows(ProcessCompiler.InvalidProcessException.class, () -> ProcessCompiler.compile(m));
        var g = ProcessModel.builder("p").start("s").task("t", ElementType.SERVICE_TASK, Map.of("onError", "continue")).end("e")
                .flow("s", "t").flow("t", "e").build();
        assertDoesNotThrow(() -> ProcessCompiler.compile(g));
    }
}
