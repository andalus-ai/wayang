package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.dmn.DecisionTable;
import tech.kayys.wayang.eip.IdempotencyStore;

class RuntimeTest {

    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();

    private ReferenceEngine engine(Map<String, ServiceHandler.Connector> connectors) {
        DecisionModel risk = DecisionModel.builder("risk")
                .table(DecisionTable.builder("risk").input("applicant.age").output("risk")
                        .rule(List.of("< 25"), List.of("\"high\"")).rule(List.of(">= 25"), List.of("\"low\"")).build())
                .build();
        NodeRunner runner = new NodeRunner(List.of(new ControlHandler(waits), new HumanHandler(waits),
                new DmnHandler(Map.of("risk", risk)), new EipHandler(new IdempotencyStore.InMemory(10)),
                new AgentHandler((spec, in) -> Map.of("output", "summary of: " + in.get("prompt")), Map.of()),
                new ServiceHandler(connectors)));
        return new ReferenceEngine(runner, waits);
    }

    private static ProcessModel loan() {
        return ProcessModel.builder("loan").start("s")
                .task("assess", ElementType.BUSINESS_RULE_TASK, Map.of("model", "risk"))
                .exclusive("g")
                .task("review", ElementType.USER_TASK, Map.of("title", "Review {{ applicant.name }}"))
                .task("grant", ElementType.SERVICE_TASK, Map.of("connector", "ledger", "inputs", Map.of("who", "applicant.name")))
                .exclusive("m").end("e")
                .flow("s", "assess").flow("assess", "g")
                .flow("g", "review", "assess.result = \"high\"").defaultFlow("g", "grant")
                .flow("review", "m").flow("grant", "m").flow("m", "e").build();
    }

    @Test
    void lowRiskGoesStraightToGrant() {
        var calls = new java.util.ArrayList<Object>();
        var eng = engine(Map.of("ledger", in -> {
            calls.add(in.get("who"));
            return Map.of("ok", true);
        }));
        var run = eng.start(ProcessCompiler.compile(loan()), Map.of("applicant", Map.of("name", "Ana", "age", 40)));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals(List.of("Ana"), calls);
        assertEquals(List.of("s", "assess", "g", "grant", "m", "e"), run.executedNodes());
        assertEquals("low", run.variables().get("assess.risk"));
    }

    @Test
    void highRiskParksOnHumanTaskThenResumes() {
        var eng = engine(Map.of("ledger", in -> Map.of("ok", true)));
        var run = eng.start(ProcessCompiler.compile(loan()), Map.of("applicant", Map.of("name", "Bo", "age", 20)));
        assertEquals(ReferenceEngine.Status.WAITING, run.status());
        assertEquals("Review Bo", run.waitingOn().info().get("title"));
        assertEquals(1, waits.size());
        eng.complete(run, run.waitingOn().token(), Map.of("decision", "approved"));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals("approved", run.variables().get("review.decision"));
        assertFalse(run.executedNodes().contains("grant"));
        assertTrue(run.executedNodes().contains("e"));
        assertEquals(0, waits.size());
    }

    @Test
    void wrongTokenIsRejected() {
        var eng = engine(Map.of("ledger", in -> Map.of()));
        var run = eng.start(ProcessCompiler.compile(loan()), Map.of("applicant", Map.of("name", "Bo", "age", 20)));
        assertThrows(IllegalStateException.class, () -> eng.complete(run, "nope", Map.of()));
    }

    @Test
    void connectorFailureFailsTheRun() {
        var eng = engine(Map.of("ledger", in -> {
            throw new java.io.IOException("down");
        }));
        var run = eng.start(ProcessCompiler.compile(loan()), Map.of("applicant", Map.of("name", "Ana", "age", 40)));
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertTrue(run.error().contains("down"));
    }

    @Test
    void eipAndAgentNodes() {
        var m = ProcessModel.builder("p").start("s")
                .task("split", ElementType.EIP_TASK, Map.of("message", "order",
                        "pipeline", Map.of("steps", List.of(Map.of("type", "splitter", "items", "body.lines")))))
                .task("ask", ElementType.AGENT_TASK, Map.of("agent", Map.of("id", "a1", "model", "x/y",
                        "inputTemplate", "Lines: {{ split.count }}")))
                .end("e").flow("s", "split").flow("split", "ask").flow("ask", "e").build();
        var run = engine(Map.of()).start(ProcessCompiler.compile(m), Map.of("order", Map.of("lines", List.of(1, 2, 3))));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
        assertEquals("summary of: Lines: 3", run.variables().get("ask.output"));
    }

    @Test
    void missingHandlerFailsClearly() {
        var m = ProcessModel.builder("p").start("s").task("x", ElementType.SERVICE_TASK, Map.of("executorType", "nope"))
                .end("e").flow("s", "x").flow("x", "e").build();
        var run = engine(Map.of()).start(ProcessCompiler.compile(m), Map.of());
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertTrue(run.error().contains("No handler"));
    }

    @Test
    void timerParksAndResumes() {
        var m = ProcessModel.builder("p").start("s").task("t", ElementType.TIMER_EVENT, Map.of("duration", "PT1H"))
                .end("e").flow("s", "t").flow("t", "e").build();
        var eng = engine(Map.of());
        var run = eng.start(ProcessCompiler.compile(m), Map.of());
        assertEquals("timer", run.waitingOn().kind());
        eng.complete(run, run.waitingOn().token(), Map.of());
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status());
    }
}
