package tech.kayys.wayang.bpmn;

import static org.junit.jupiter.api.Assertions.*;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class BpmnTest {

    /** Single-threaded reference interpreter: runs the plan the way a dependency-only scheduler would. */
    private static Map<String, Object> run(ExecutionPlan plan, Map<String, Object> inputs, List<String> executed) {
        Map<String, Object> vars = new LinkedHashMap<>(inputs);
        for (PlanNode n : plan.nodes()) { // plan is in topological order
            Map<String, Object> out;
            if (FlowRuntime.isActive(n, vars)) {
                executed.add(n.id());
                out = FlowRuntime.liveOutputs(n, vars, Map.of());
            } else {
                out = FlowRuntime.deadOutputs(n);
            }
            out.forEach((k, v) -> vars.put(n.id() + "." + k, v));
        }
        return vars;
    }

    private static ProcessModel xor() {
        return ProcessModel.builder("loan")
                .start("s").exclusive("g")
                .task("approve", ElementType.SERVICE_TASK, Map.of())
                .task("reject", ElementType.SERVICE_TASK, Map.of())
                .exclusive("m").end("e")
                .flow("s", "g")
                .flow("g", "approve", "amount < 1000")
                .defaultFlow("g", "reject")
                .flow("approve", "m").flow("reject", "m").flow("m", "e").build();
    }

    @Test
    void exclusiveGatewayTakesOnePath() {
        ExecutionPlan plan = ProcessCompiler.compile(xor());
        List<String> ran = new java.util.ArrayList<>();
        run(plan, Map.of("amount", 500), ran);
        assertEquals(List.of("s", "g", "approve", "m", "e"), ran);
        ran.clear();
        run(plan, Map.of("amount", 5000), ran);
        assertEquals(List.of("s", "g", "reject", "m", "e"), ran);
    }

    @Test
    void parallelSplitAndJoin() {
        var m = ProcessModel.builder("p").start("s").parallel("fork")
                .task("a", ElementType.SERVICE_TASK, Map.of()).task("b", ElementType.SERVICE_TASK, Map.of())
                .parallel("join").end("e")
                .flow("s", "fork").flow("fork", "a").flow("fork", "b").flow("a", "join").flow("b", "join")
                .flow("join", "e").build();
        List<String> ran = new java.util.ArrayList<>();
        run(ProcessCompiler.compile(m), Map.of(), ran);
        assertTrue(ran.containsAll(List.of("a", "b", "join", "e")));
    }

    @Test
    void inclusiveGatewayTakesAllMatching() {
        var m = ProcessModel.builder("i").start("s").inclusive("g")
                .task("a", ElementType.SERVICE_TASK, Map.of()).task("b", ElementType.SERVICE_TASK, Map.of())
                .task("c", ElementType.SERVICE_TASK, Map.of()).inclusive("j").end("e")
                .flow("s", "g").flow("g", "a", "x > 0").flow("g", "b", "x > 5").defaultFlow("g", "c")
                .flow("a", "j").flow("b", "j").flow("c", "j").flow("j", "e").build();
        var plan = ProcessCompiler.compile(m);
        List<String> ran = new java.util.ArrayList<>();
        run(plan, Map.of("x", 10), ran);
        assertTrue(ran.contains("a") && ran.contains("b") && !ran.contains("c") && ran.contains("e"));
        ran.clear();
        run(plan, Map.of("x", -1), ran);
        assertTrue(ran.contains("c") && !ran.contains("a") && ran.contains("e"));
    }

    @Test
    void conditionsSeeTaskOutputsAndNestedInputs() {
        var m = ProcessModel.builder("n").start("s")
                .task("score", ElementType.SERVICE_TASK, Map.of()).exclusive("g")
                .task("hi", ElementType.SERVICE_TASK, Map.of()).task("lo", ElementType.SERVICE_TASK, Map.of())
                .end("e")
                .flow("s", "score").flow("score", "g").flow("g", "hi", "score.value > customer.limit")
                .defaultFlow("g", "lo").flow("hi", "e").flow("lo", "e").build();
        var plan = ProcessCompiler.compile(m);
        Map<String, Object> vars = new LinkedHashMap<>(Map.of("customer", Map.of("limit", 10)));
        for (PlanNode n : plan.nodes()) {
            Map<String, Object> out = FlowRuntime.isActive(n, vars)
                    ? FlowRuntime.liveOutputs(n, vars, n.id().equals("score") ? Map.of("value", 42) : Map.of())
                    : FlowRuntime.deadOutputs(n);
            out.forEach((k, v) -> vars.put(n.id() + "." + k, v));
        }
        assertEquals(true, vars.get("hi.active"));
        assertEquals(false, vars.get("lo.active"));
    }

    @Test
    void exclusiveWithoutMatchOrDefaultFails() {
        var m = ProcessModel.builder("x").start("s").exclusive("g")
                .task("a", ElementType.SERVICE_TASK, Map.of()).task("b", ElementType.SERVICE_TASK, Map.of()).end("e")
                .flow("s", "g").flow("g", "a", "x > 5").flow("g", "b", "x > 10").flow("a", "e").flow("b", "e").build();
        var plan = ProcessCompiler.compile(m);
        assertThrows(FlowRuntime.ProcessFlowException.class, () -> run(plan, Map.of("x", 1), new java.util.ArrayList<>()));
    }

    @Test
    void validationCatchesProblems() {
        var cyc = ProcessModel.builder("c").start("s").task("a", ElementType.SERVICE_TASK, Map.of())
                .task("b", ElementType.SERVICE_TASK, Map.of()).end("e")
                .flow("s", "a").flow("a", "b").flow("b", "a").flow("b", "e").build();
        assertTrue(cyc.validate().stream().anyMatch(i -> i.message().contains("Cycle")));
        assertThrows(ProcessCompiler.InvalidProcessException.class, () -> ProcessCompiler.compile(cyc));

        var noEnd = ProcessModel.builder("d").start("s").task("a", ElementType.SERVICE_TASK, Map.of()).flow("s", "a").build();
        assertFalse(noEnd.isValid());

        var badCond = ProcessModel.builder("b").start("s").exclusive("g").end("e1").end("e2")
                .flow("s", "g").flow("g", "e1", "x >").defaultFlow("g", "e2").build();
        assertTrue(badCond.validate().stream().anyMatch(i -> i.message().contains("Invalid condition")));

        var dots = ProcessModel.builder("d").start("s.1").end("e").flow("s.1", "e").build();
        assertFalse(dots.isValid());

        var noCondOnSplit = ProcessModel.builder("n").start("s").exclusive("g").end("e1").end("e2")
                .flow("s", "g").flow("g", "e1").flow("g", "e2").build();
        assertFalse(noCondOnSplit.isValid());
    }

    @Test
    void planNodeRoundTripsThroughConfiguration() {
        var plan = ProcessCompiler.compile(xor());
        for (PlanNode n : plan.nodes()) {
            PlanNode back = PlanNode.fromConfiguration(n.toConfiguration());
            assertEquals(n.id(), back.id());
            assertEquals(n.type(), back.type());
            assertEquals(n.incoming(), back.incoming());
            assertEquals(n.outgoing(), back.outgoing());
            assertEquals(n.executorType(), back.executorType());
        }
    }

    @Test
    void executorTypeFollowsElementKind() {
        var m = ProcessModel.builder("k").start("s")
                .task("rule", ElementType.BUSINESS_RULE_TASK, Map.of()).task("ask", ElementType.USER_TASK, Map.of())
                .task("call", ElementType.SERVICE_TASK, Map.of("executorType", "custom-rest")).end("e")
                .flow("s", "rule").flow("rule", "ask").flow("ask", "call").flow("call", "e").build();
        var plan = ProcessCompiler.compile(m);
        assertEquals("wayang-dmn", plan.node("rule").get().executorType());
        assertEquals("wayang-human", plan.node("ask").get().executorType());
        assertEquals("custom-rest", plan.node("call").get().executorType());
        assertEquals("wayang-bpmn", plan.node("s").get().executorType());
    }
}
