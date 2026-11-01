package tech.kayys.wayang.agent;

import java.util.Map;

/**
 * Port to whatever actually runs agents (Andalus in production). Implementations
 * should honour {@link AgentSpec#maxSteps()}, {@link AgentSpec#timeoutSeconds()}
 * and {@link AgentSpec#requireApproval()}.
 */
@FunctionalInterface
public interface AgentInvoker {

    /**
     * @param spec  the agent to run
     * @param input rendered prompt under {@code prompt}, plus the raw workflow variables under {@code variables}
     * @return result map; by convention {@code output} holds the final answer
     */
    Map<String, Object> invoke(AgentSpec spec, Map<String, Object> input);
}
