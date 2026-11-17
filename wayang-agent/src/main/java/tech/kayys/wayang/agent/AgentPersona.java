package tech.kayys.wayang.agent;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

/**
 * The {@code persona} option of an agent: {@code {role, tone, language, boundaries: [...]}}. It is structured so a builder can edit it
 * as a form and so it is composed into the instructions the same way every time. All fields are optional, but at least one must be set.
 *
 * <p>Persona text is literal: it is <em>not</em> run through the prompt template, so it cannot read workflow variables, and it is placed
 * before the (rendered) system prompt. It guides the model; it is not a security boundary (see {@link AgentGuardrails} for what is enforced).
 */
public record AgentPersona(String role, String tone, String language, List<String> boundaries) {

    public static final int MAX_FIELD = 200;
    public static final int MAX_BOUNDARIES = 20;

    /** @return null when the agent has no persona option; @throws AgentSpecException when it is malformed */
    public static AgentPersona of(AgentSpec spec) {
        return of(spec.id(), spec.options());
    }

    static AgentPersona of(String agentId, Map<String, Object> options) {
        Object o = options.get("persona");
        if (o == null) {
            return null;
        }
        String where = "Agent '" + agentId + "': options.persona";
        if (!(o instanceof Map<?, ?> m)) {
            throw new AgentSpecException(where + " must be an object");
        }
        for (Object k : m.keySet()) {
            if (!List.of("role", "tone", "language", "boundaries").contains(String.valueOf(k))) {
                throw new AgentSpecException(where + " has an unknown field '" + k + "'");
            }
        }
        String role = text(m.get("role"), where + ".role");
        String tone = text(m.get("tone"), where + ".tone");
        String language = text(m.get("language"), where + ".language");
        List<String> boundaries = new ArrayList<>();
        Object b = m.get("boundaries");
        if (b != null) {
            if (!(b instanceof List<?> l) || l.size() > MAX_BOUNDARIES) {
                throw new AgentSpecException(where + ".boundaries must be a list of up to " + MAX_BOUNDARIES + " lines");
            }
            for (Object e : l) {
                String s = text(e, where + ".boundaries");
                if (s == null) {
                    throw new AgentSpecException(where + ".boundaries lines must not be empty");
                }
                boundaries.add(s);
            }
        }
        if (role == null && tone == null && language == null && boundaries.isEmpty()) {
            throw new AgentSpecException(where + " needs at least one of role, tone, language, boundaries");
        }
        return new AgentPersona(role, tone, language, List.copyOf(boundaries));
    }

    private static String text(Object o, String where) {
        if (o == null) {
            return null;
        }
        if (!(o instanceof String s)) {
            throw new AgentSpecException(where + " must be text");
        }
        String t = s.strip();
        if (t.length() > MAX_FIELD) {
            throw new AgentSpecException(where + " is longer than " + MAX_FIELD + " characters");
        }
        return t.isEmpty() ? null : t;
    }

    /** The instructions block for this persona, followed by {@code systemPrompt} (already rendered) when it is not blank. */
    public String compose(String systemPrompt) {
        StringBuilder sb = new StringBuilder();
        if (role != null) {
            sb.append("Role: ").append(role).append('\n');
        }
        if (tone != null) {
            sb.append("Tone: ").append(tone).append('\n');
        }
        if (language != null) {
            sb.append("Reply in: ").append(language).append('\n');
        }
        if (!boundaries.isEmpty()) {
            sb.append("Boundaries (never do these):\n");
            boundaries.forEach(x -> sb.append("- ").append(x).append('\n'));
        }
        if (systemPrompt != null && !systemPrompt.isBlank()) {
            sb.append('\n').append(systemPrompt);
        }
        return sb.toString().stripTrailing();
    }
}
