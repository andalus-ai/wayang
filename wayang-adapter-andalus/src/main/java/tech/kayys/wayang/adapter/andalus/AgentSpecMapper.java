package tech.kayys.wayang.adapter.andalus;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.andalus.core.AgentDefinition;
import tech.kayys.andalus.extension.Reference;
import tech.kayys.andalus.spi.agent.AgentIdentity;
import tech.kayys.wayang.agent.AgentPersona;
import tech.kayys.wayang.agent.AgentSpec;

/**
 * Translates a Wayang {@link AgentSpec} into an Andalus {@link AgentDefinition}. Pure: no registry or network access.
 *
 * <ul>
 * <li>{@code goal} and {@code instructions} both carry the prompt the model is told. Andalus's ReAct execution uses {@code goal} as the system prompt
 * (see {@code docs/ANDALUS-AGENT-MAPPING.md}); {@code instructions} is filled too so a runtime that reads it sees the same text. The prompt is
 * the one Wayang already rendered for this run (persona block + templated system prompt), or, when none is given, the persona block over the raw
 * system prompt.</li>
 * <li>{@code role} is the persona role, {@code model} and every tool are by-name references into the target deployment's registry (nothing is resolved here),</li>
 * <li>{@code constraints} carries {@code maxSteps}, {@code timeoutSeconds}, {@code requireApproval} and {@code guardrails}. <strong>These are data only</strong>:
 * Andalus does not read {@code constraints} today, so none of them is enforced by Andalus through this mapping (Wayang still enforces the output
 * guardrails itself).</li>
 * </ul>
 */
public final class AgentSpecMapper {

    private AgentSpecMapper() {
    }

    /**
     * @param renderedSystem the prompt Wayang rendered for this run, or null to compose it from the spec
     */
    public static AgentDefinition toDefinition(AgentSpec spec, String renderedSystem) {
        // an AgentSpec id ([A-Za-z][A-Za-z0-9_-]{0,63}) is always a valid Andalus agent id
        AgentIdentity identity = new AgentIdentity(spec.id(), spec.name(), null);
        AgentPersona persona = AgentPersona.of(spec);
        String prompt = renderedSystem != null && !renderedSystem.isBlank() ? renderedSystem
                : persona != null ? persona.compose(spec.systemPrompt())
                : spec.systemPrompt() == null ? "" : spec.systemPrompt();

        AgentDefinition.Builder b = AgentDefinition.builder()
                .identity(identity)
                .goal(prompt)
                .instructions(prompt);
        if (persona != null && persona.role() != null) {
            b.role(persona.role());
        }
        if (spec.model() != null && !spec.model().isBlank()) {
            b.model(new Reference(spec.model()));
        }
        List<Reference> tools = new ArrayList<>();
        for (AgentSpec.Tool t : spec.tools()) {
            tools.add(new Reference(t.name()));
        }
        b.tools(tools);
        b.constraints(constraints(spec));
        return b.build();
    }

    static Map<String, Object> constraints(AgentSpec spec) {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("maxSteps", spec.maxSteps());
        c.put("timeoutSeconds", spec.timeoutSeconds());
        if (!spec.requireApproval().isEmpty()) {
            c.put("requireApproval", List.copyOf(spec.requireApproval()));
        }
        Object g = spec.options().get("guardrails");
        if (g != null) {
            c.put("guardrails", g);
        }
        return c;
    }
}
