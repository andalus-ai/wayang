package tech.kayys.wayang.examples;

import java.util.List;
import java.util.Map;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.dmn.DmnLoader;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.runtime.AgentHandler;
import tech.kayys.wayang.runtime.ControlHandler;
import tech.kayys.wayang.runtime.DmnHandler;
import tech.kayys.wayang.runtime.EipHandler;
import tech.kayys.wayang.runtime.HumanHandler;
import tech.kayys.wayang.runtime.NodeRunner;
import tech.kayys.wayang.runtime.ReferenceEngine;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;

/**
 * End-to-end example: a loan application that is scored by a DMN decision,
 * summarised by an agent, split into line items by an EIP splitter and
 * routed either to automatic approval or to a human reviewer.
 *
 * <p>
 * Runs on the in-memory {@link ReferenceEngine}; deploying the same
 * {@link ExecutionPlan} to Gamelan only swaps the engine
 * (see {@code GamelanPlanMapper}).
 */
public final class LoanApproval {

    private LoanApproval() {
    }

    public static DecisionModel decisions() {
        return DmnLoader.load(Map.of("id", "loan", "decisions", List.of(
                Map.of("name", "risk", "type", "table", "hitPolicy", "FIRST",
                        "inputs", List.of("applicant.age", "amount"), "outputs", List.of("risk"),
                        "rules", List.of(
                                Map.of("when", List.of("< 25", "> 5000"), "then", List.of("\"high\"")),
                                Map.of("when", List.of("-", "> 50000"), "then", List.of("\"high\"")),
                                Map.of("when", List.of("-", "-"), "then", List.of("\"low\""))))
        )));
    }

    public static ProcessModel process() {
        return ProcessModel.builder("loan-approval").name("Loan approval").start("received")
                .task("lines", ElementType.EIP_TASK, Map.of("message", "application", "pipeline",
                        Map.of("steps", List.of(Map.of("type", "splitter", "items", "body.items")))))
                .task("score", ElementType.BUSINESS_RULE_TASK, Map.of("model", "loan"))
                .task("summary", ElementType.AGENT_TASK, Map.of("agent", Map.of("id", "summariser", "model", "any/model",
                        "inputTemplate", "Applicant {{ applicant.name }} asks {{ amount }} in {{ lines.count }} items")))
                .exclusive("route")
                .task("review", ElementType.USER_TASK, Map.of("title", "Review {{ applicant.name }}: {{ summary.output }}"))
                .task("pay", ElementType.SERVICE_TASK, Map.of("connector", "payments", "inputs", Map.of("to", "applicant.name")))
                .exclusive("join").end("done")
                .flow("received", "lines").flow("lines", "score").flow("score", "summary").flow("summary", "route")
                .flow("route", "review", "score.result = \"high\"").defaultFlow("route", "pay")
                .flow("review", "join").flow("pay", "join").flow("join", "done")
                .build();
    }

    public static NodeRunner runner(WaitRegistry waits) {
        return new NodeRunner(List.of(new ControlHandler(waits), new HumanHandler(waits),
                new DmnHandler(Map.of("loan", decisions())), new EipHandler(new IdempotencyStore.InMemory(1000)),
                new AgentHandler((spec, in) -> Map.of("output", "[summary] " + in.get("prompt")), Map.of()),
                new ServiceHandler(Map.of("payments", in -> Map.of("paid", true, "to", in.get("to"))))));
    }

    public static void main(String[] args) {
        WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
        ReferenceEngine engine = new ReferenceEngine(runner(waits), waits);
        ExecutionPlan plan = ProcessCompiler.compile(process());
        var input = Map.<String, Object>of("applicant", Map.of("name", "Ana", "age", 31), "amount", 1200,
                "application", Map.of("items", List.of("laptop", "desk")));
        var run = engine.start(plan, input);
        System.out.println("status=" + run.status() + " executed=" + run.executedNodes());
        System.out.println("paid=" + run.variables().get("pay.paid"));
    }
}
