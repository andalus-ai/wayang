package tech.kayys.wayang.examples;

import java.util.List;
import java.util.Map;
import java.util.function.Consumer;

import tech.kayys.wayang.bpmn.ElementType;
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
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;

/**
 * A chat assistant with a safety net: the agent only <em>proposes</em> a reply; a deterministic decision table and,
 * when it says so, a person decide whether it goes out.
 *
 * <pre>
 * chat message ──&gt; agent drafts ──&gt; policy (DMN) ──&gt; auto ───────────────&gt; send
 *                                        └──&gt; approve ──&gt; human ──&gt; approved ──&gt; send
 *                                                              └──&gt; rejected ──&gt; end (nothing sent)
 * </pre>
 *
 * The policy looks at the incoming text and the draft, never at what the agent claims about itself, so a message that
 * tries to talk the agent into skipping approval cannot: money words in the message or a link in the draft always
 * require a human. Input is a trigger's {@code event} ({@code chatId}, {@code text}).
 */
public final class PersonalAssistant {

    private PersonalAssistant() {
    }

    public static DecisionModel policy() {
        return DmnLoader.load(Map.of("id", "assistant", "decisions", List.of(
                Map.of("name", "policy", "type", "table", "hitPolicy", "FIRST",
                        "inputs", List.of(
                                "contains(draft.output, \"http\")",
                                "contains(event.text, \"pay\") or contains(event.text, \"Pay\") or contains(event.text, \"transfer\")"
                                        + " or contains(event.text, \"password\") or contains(event.text, \"delete\")"),
                        "outputs", List.of("result"),
                        "rules", List.of(
                                Map.of("when", List.of("true", "-"), "then", List.of("\"approve\"")),
                                Map.of("when", List.of("-", "true"), "then", List.of("\"approve\"")),
                                Map.of("when", List.of("-", "-"), "then", List.of("\"auto\"")))))));
    }

    public static ProcessModel process() {
        return ProcessModel.builder("personal-assistant").name("Personal assistant").start("received")
                .task("draft", ElementType.AGENT_TASK, Map.of("agent", Map.of("id", "replier", "model", "any/model",
                        "inputTemplate", "Reply briefly to: {{ event.text }}")))
                .task("policy", ElementType.BUSINESS_RULE_TASK, Map.of("model", "assistant"))
                .exclusive("route")
                .task("approve", ElementType.USER_TASK, Map.of("title", "Send this reply? {{ draft.output }}"))
                .exclusive("decision")
                .exclusive("join")
                .task("send", ElementType.SERVICE_TASK, Map.of("connector", "telegram",
                        "inputs", Map.of("chatId", "event.chatId", "text", "draft.output")))
                .end("sent").end("dropped")
                .flow("received", "draft").flow("draft", "policy").flow("policy", "route")
                .flow("route", "approve", "policy.result = \"approve\"").defaultFlow("route", "join")
                .flow("approve", "decision")
                .flow("decision", "join", "approve.approved = true").defaultFlow("decision", "dropped")
                .flow("join", "send").flow("send", "sent")
                .build();
    }

    /** A runner with a drafting function and a function that "sends" (for the example and tests). */
    public static NodeRunner runner(WaitRegistry waits, java.util.function.Function<String, String> drafter, Consumer<Map<String, Object>> sender) {
        return new NodeRunner(List.of(new ControlHandler(waits), new HumanHandler(waits),
                new DmnHandler(Map.of("assistant", policy())), new EipHandler(new IdempotencyStore.InMemory(100)),
                new AgentHandler((spec, in) -> Map.of("output", drafter.apply(String.valueOf(in.get("prompt")))), Map.of()),
                new ServiceHandler(Map.of("telegram", in -> {
                    sender.accept(in);
                    return Map.of("sent", true);
                }))));
    }
}
