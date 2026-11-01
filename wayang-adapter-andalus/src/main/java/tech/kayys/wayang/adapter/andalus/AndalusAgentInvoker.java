package tech.kayys.wayang.adapter.andalus;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.kayys.andalus.execution.ExecutionContext;
import tech.kayys.andalus.execution.VariableEntry;
import tech.kayys.andalus.service.ExecutionService;
import tech.kayys.wayang.agent.AgentInvoker;
import tech.kayys.wayang.agent.AgentSpec;

/**
 * {@link AgentInvoker} that runs an agent through Andalus's
 * {@link ExecutionService}.
 *
 * <p>
 * <strong>Scope and limits.</strong> The agent is looked up in Andalus by
 * {@code spec.id()}: it must already be registered there. Translating a Wayang
 * {@link AgentSpec} into an Andalus {@code AgentDefinition} (identity, model,
 * tool and skill references) is not done by this class, because those
 * references point at Andalus registry entries that only the target deployment
 * knows. {@code maxSteps}, {@code timeoutSeconds} and {@code requireApproval}
 * are passed as inputs (keys {@code wayang.maxSteps} etc.) for the agent's
 * guardrails to honour, but nothing here enforces them.
 */
public final class AndalusAgentInvoker implements AgentInvoker {

    private final ExecutionService executions;

    public AndalusAgentInvoker(ExecutionService executions) {
        this.executions = executions;
    }

    @Override
    public Map<String, Object> invoke(AgentSpec spec, Map<String, Object> input) {
        Map<String, Object> in = new LinkedHashMap<>(input);
        in.put("wayang.maxSteps", spec.maxSteps());
        in.put("wayang.timeoutSeconds", spec.timeoutSeconds());
        in.put("wayang.requireApproval", spec.requireApproval());
        ExecutionContext ctx = executions.startAgent(spec.id(), in);
        Map<String, Object> out = new LinkedHashMap<>();
        for (VariableEntry<?> e : ctx.variables().entries()) {
            out.put(e.getKey().name(), e.getValue());
        }
        // Convention used by Wayang nodes: the final answer is "output"; fall back to "result".
        if (!out.containsKey("output") && out.containsKey("result")) {
            out.put("output", out.get("result"));
        }
        return out;
    }
}
