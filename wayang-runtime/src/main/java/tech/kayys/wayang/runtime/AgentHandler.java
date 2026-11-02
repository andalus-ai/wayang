package tech.kayys.wayang.runtime;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.kayys.wayang.agent.AgentInvoker;
import tech.kayys.wayang.agent.AgentSpec;
import tech.kayys.wayang.agent.AgentSpecException;
import tech.kayys.wayang.agent.AgentSpecLoader;
import tech.kayys.wayang.agent.PromptTemplate;
import tech.kayys.wayang.bpmn.FlowRuntime;
import tech.kayys.wayang.bpmn.PlanNode;

/**
 * Executor {@code wayang-agent}. Config: {@code agent} = an inline agent spec
 * map, or a string id resolved from the registry. Renders the prompt from the
 * workflow variables, delegates to the {@link AgentInvoker} (Andalus) and
 * exposes the result map as outputs; {@code output} is the final answer by
 * convention.
 */
public final class AgentHandler implements DomainHandler {

    private final AgentInvoker invoker;
    private final java.util.function.Function<String, AgentSpec> registry;

    public AgentHandler(AgentInvoker invoker, Map<String, AgentSpec> registry) {
        this.invoker = invoker;
        Map<String, AgentSpec> copy = Map.copyOf(registry);
        this.registry = copy::get;
    }

    /** Resolves agent specs on demand; may return null. */
    public AgentHandler(AgentInvoker invoker, java.util.function.Function<String, AgentSpec> lookup) {
        this.invoker = invoker;
        this.registry = lookup;
    }

    private volatile tech.kayys.wayang.agent.ConversationMemory memory;

    /** Gives agents with a {@code memory} option their conversation history; without it they stay stateless. */
    public AgentHandler withMemory(tech.kayys.wayang.agent.ConversationMemory m) {
        this.memory = m;
        return this;
    }

    @Override
    public String executorType() {
        return "wayang-agent";
    }

    @Override
    public NodeOutcome handle(String runId, PlanNode node, Map<String, Object> vars) {
        try {
            Object a = node.config().get("agent");
            AgentSpec spec;
            if (a instanceof Map<?, ?> m) {
                Map<String, Object> mm = new LinkedHashMap<>();
                m.forEach((k, v) -> mm.put(String.valueOf(k), v));
                spec = AgentSpecLoader.fromMap(mm);
            } else if (a != null && registry.apply(String.valueOf(a)) != null) {
                spec = registry.apply(String.valueOf(a));
            } else {
                return new NodeOutcome.Failed("Unknown agent '" + a + "' in node " + node.id(), false);
            }
            var scope = FlowRuntime.scopeOf(vars);
            Map<String, Object> input = new LinkedHashMap<>();
            input.put("system", PromptTemplate.render(spec.systemPrompt(), scope));
            input.put("prompt", PromptTemplate.render(spec.inputTemplate(), scope));
            input.put("variables", FlowRuntime.nest(vars));
            tech.kayys.wayang.agent.AgentMemory mem = memory == null ? null : tech.kayys.wayang.agent.AgentMemory.of(spec);
            String convKey = null;
            String ns = null;
            if (mem != null) {
                convKey = PromptTemplate.render(mem.keyTemplate(), scope).strip();
                if (convKey.isEmpty() || convKey.length() > 200) {
                    return new NodeOutcome.Failed("Agent " + spec.id() + ": the memory key is empty or longer than 200 characters", false);
                }
                // private to tenant + process + agent: no conversation is ever read across them
                ns = (vars.get(SecretResolver.TENANT_VARIABLE) instanceof String t ? t : "") + "|"
                        + (vars.get(SecretResolver.PROCESS_VARIABLE) instanceof String pr ? pr : "") + "|" + spec.id();
                if (Boolean.TRUE.equals(node.config().get("forget"))) {
                    // not a model call: the person asked to be forgotten
                    boolean had = memory.clear(ns, convKey);
                    return new NodeOutcome.Completed(Map.of("output", had ? "Done. I have forgotten this conversation." : "There was nothing to forget.", "forgotten", had));
                }
                input.put("history", tech.kayys.wayang.agent.ConversationMemory.asMessages(memory.load(ns, convKey, mem.turns())));
            } else if (Boolean.TRUE.equals(node.config().get("forget"))) {
                return new NodeOutcome.Completed(Map.of("output", "I do not keep any memory of conversations.", "forgotten", false));
            }
            Map<String, Object> result = invoker.invoke(spec, input);
            if (mem != null && result != null && result.get("output") instanceof String answer && !answer.isBlank()) {
                memory.append(ns, convKey, new tech.kayys.wayang.agent.ConversationMemory.Turn(String.valueOf(input.get("prompt")), answer,
                        System.currentTimeMillis()), mem.turns());
            }
            return new NodeOutcome.Completed(result == null ? Map.of() : result);
        } catch (AgentSpecException e) {
            return new NodeOutcome.Failed(e.getMessage(), false);
        } catch (RuntimeException e) {
            // model/tool failures are usually transient
            return new NodeOutcome.Failed("Agent failed: " + e.getMessage(), true);
        }
    }
}
