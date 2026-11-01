package tech.kayys.wayang.agent;

import java.util.Map;

/**
 * The {@code memory} option of an agent: {@code {key: "{{ event.chatId }}", turns: 10}}. {@code key} is a prompt template rendered with the
 * run's variables (it names the conversation: a chat, a user, a ticket); {@code turns} (1..50, default 10) is how many exchanges are
 * remembered. An agent without it remembers nothing between runs.
 */
public record AgentMemory(String keyTemplate, int turns) {

    public static final int MAX_TURNS = 50;

    /** @return null when the agent has no memory option; @throws AgentSpecException when it is malformed */
    public static AgentMemory of(AgentSpec spec) {
        return of(spec.id(), spec.options());
    }

    /** Same, for an agent still being built (its options, before the spec exists). */
    static AgentMemory of(String agentId, Map<String, Object> options) {
        Object o = options.get("memory");
        if (o == null) {
            return null;
        }
        if (!(o instanceof Map<?, ?> m) || !(m.get("key") instanceof String key) || key.isBlank() || key.length() > 200) {
            throw new AgentSpecException("Agent '" + agentId + "': options.memory needs a 'key' template (up to 200 characters)");
        }
        int turns = 10;
        if (m.get("turns") != null) {
            if (!(m.get("turns") instanceof Number n) || n.intValue() < 1 || n.intValue() > MAX_TURNS) {
                throw new AgentSpecException("Agent '" + agentId + "': options.memory.turns must be 1.." + MAX_TURNS);
            }
            turns = n.intValue();
        }
        return new AgentMemory(key, turns);
    }
}
