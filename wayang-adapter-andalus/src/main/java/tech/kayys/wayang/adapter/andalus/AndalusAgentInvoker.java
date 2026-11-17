package tech.kayys.wayang.adapter.andalus;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.kayys.andalus.execution.GraphExecutionContext;
import tech.kayys.andalus.execution.VariableEntry;
import tech.kayys.andalus.service.ExecutionService;
import tech.kayys.wayang.agent.AgentInvoker;
import tech.kayys.wayang.agent.AgentSpec;

/**
 * {@link AgentInvoker} that runs an agent through Andalus's
 * {@link ExecutionService}.
 *
 * <p>
 * <strong>Scope and limits.</strong> By default the agent is looked up in Andalus by
 * {@code spec.id()}: it must already be registered there. {@link #byDefinition} instead
 * passes a definition built by {@link AgentSpecMapper}; its model and tool references are by
 * name and only resolve if the target deployment has entries of those names. {@code maxSteps}, {@code timeoutSeconds} and {@code requireApproval}
 * are passed as inputs (keys {@code wayang.maxSteps} etc.) for the agent's
 * guardrails to honour, but nothing here enforces them.
 */
public final class AndalusAgentInvoker implements AgentInvoker {

    private final ExecutionService executions;
    private final boolean byDefinition;

    /** Runs agents that are already registered in Andalus, looked up by {@code spec.id()}. */
    public AndalusAgentInvoker(ExecutionService executions) {
        this(executions, false);
    }

    private AndalusAgentInvoker(ExecutionService executions, boolean byDefinition) {
        this.executions = executions;
        this.byDefinition = byDefinition;
    }

    /**
     * Hands Andalus an {@code AgentDefinition} built from each {@link AgentSpec} ({@link AgentSpecMapper}) instead of looking the agent up by id, so
     * nothing has to be registered in Andalus first. Needs an {@link ExecutionService} that implements {@code startAgent(AgentDefinition, inputs)}.
     */
    public static AndalusAgentInvoker byDefinition(ExecutionService executions) {
        return new AndalusAgentInvoker(executions, true);
    }

    @Override
    public Map<String, Object> invoke(AgentSpec spec, Map<String, Object> input) {
        Map<String, Object> in = new LinkedHashMap<>(input);
        in.put("wayang.maxSteps", spec.maxSteps());
        in.put("wayang.timeoutSeconds", spec.timeoutSeconds());
        in.put("wayang.requireApproval", spec.requireApproval());
        GraphExecutionContext ctx = byDefinition
                ? executions.startAgent(AgentSpecMapper.toDefinition(spec, input.get("system") instanceof String p ? p : null), in)
                : executions.startAgent(spec.id(), in);
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
