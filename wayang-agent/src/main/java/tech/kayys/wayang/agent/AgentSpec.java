package tech.kayys.wayang.agent;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;

/**
 * Declarative, engine-neutral agent definition: what the Studio stores. The
 * Andalus adapter translates it to an Andalus agent; this module never calls a
 * model itself.
 *
 * @param id              stable identifier ({@code [A-Za-z][A-Za-z0-9_-]*})
 * @param model           provider/model reference, e.g. {@code openai/gpt-4o}; resolved by the Andalus runtime
 * @param systemPrompt    instructions; may contain {@code {{ expression }}} placeholders (see {@link PromptTemplate})
 * @param inputTemplate   user prompt template rendered from the workflow variables
 * @param tools           tools the agent may call
 * @param maxSteps        upper bound on reasoning/tool-call iterations (1..50)
 * @param timeoutSeconds  wall-clock limit (1..3600)
 * @param requireApproval tool names that need human approval before every call
 * @param outputKeys      keys the caller expects back in the result map (documented contract, not enforced by this class)
 */
public record AgentSpec(String id, String name, String model, String systemPrompt, String inputTemplate,
        List<Tool> tools, int maxSteps, int timeoutSeconds, List<String> requireApproval, List<String> outputKeys,
        Map<String, Object> options) {

    public static final int MAX_STEPS_LIMIT = 50;
    public static final int MAX_TIMEOUT_SECONDS = 3600;
    private static final Pattern ID = Pattern.compile("[A-Za-z][A-Za-z0-9_-]{0,63}");

    /** A tool reference. {@code type} is a connector kind (http, mcp, builtin, ...), resolved by the runtime. */
    public record Tool(String name, String type, String description, Map<String, Object> config) {
        public Tool {
            if (name == null || name.isBlank()) {
                throw new AgentSpecException("Tool name is required");
            }
            config = config == null ? Map.of() : Map.copyOf(config);
        }
    }

    public AgentSpec {
        if (id == null || !ID.matcher(id).matches()) {
            throw new AgentSpecException("Agent id must match " + ID.pattern() + " but was '" + id + "'");
        }
        if (model == null || model.isBlank()) {
            throw new AgentSpecException("Agent '" + id + "' needs a model");
        }
        if (maxSteps < 1 || maxSteps > MAX_STEPS_LIMIT) {
            throw new AgentSpecException("Agent '" + id + "': maxSteps must be 1.." + MAX_STEPS_LIMIT);
        }
        if (timeoutSeconds < 1 || timeoutSeconds > MAX_TIMEOUT_SECONDS) {
            throw new AgentSpecException("Agent '" + id + "': timeoutSeconds must be 1.." + MAX_TIMEOUT_SECONDS);
        }
        name = name == null ? id : name;
        systemPrompt = systemPrompt == null ? "" : systemPrompt;
        inputTemplate = inputTemplate == null ? "{{ input }}" : inputTemplate;
        tools = tools == null ? List.of() : List.copyOf(tools);
        requireApproval = requireApproval == null ? List.of() : List.copyOf(requireApproval);
        outputKeys = outputKeys == null ? List.of() : List.copyOf(outputKeys);
        options = options == null ? Map.of() : Map.copyOf(options);
        AgentMemory.of(id, options); // a malformed memory option is a definition error, not a run-time surprise
        java.util.Set<String> names = new java.util.HashSet<>();
        for (Tool t : tools) {
            if (!names.add(t.name())) {
                throw new AgentSpecException("Agent '" + id + "': duplicate tool '" + t.name() + "'");
            }
        }
        for (String r : requireApproval) {
            if (!names.contains(r)) {
                throw new AgentSpecException("Agent '" + id + "': requireApproval references unknown tool '" + r + "'");
            }
        }
    }
}
