package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AgentTemplateTest {

    @Test
    void templateMintsAgents() {
        var t = new AgentTemplate(Map.of("id", "support-${lang}", "model", "openai/gpt-4o",
                "systemPrompt", "Answer in ${lang} for ${tier} customers."), Map.of("tier", "standard"));
        assertEquals(java.util.Set.of("lang", "tier"), t.parameters());
        AgentSpec fr = t.instantiate(Map.of("lang", "fr"));
        assertEquals("support-fr", fr.id());
        assertEquals("Answer in fr for standard customers.", fr.systemPrompt());
        assertThrows(AgentSpecException.class, () -> t.instantiate(Map.of()));
    }

    @Test
    void invalidInstantiationIsRejectedByTheSpec() {
        var t = new AgentTemplate(Map.of("id", "a-${x}", "model", "m", "maxSteps", 5), Map.of());
        assertThrows(AgentSpecException.class, () -> t.instantiate(Map.of("x", "bad id with spaces")));
    }

    @Test
    void templateValuesAreNotRescanned() {
        var t = new AgentTemplate(Map.of("id", "a", "model", "m", "systemPrompt", "Hi ${x}"), Map.of());
        assertEquals("Hi ${y}", t.instantiate(Map.of("x", "${y}")).systemPrompt());
        assertEquals("Hi $1\\", t.instantiate(Map.of("x", "$1\\")).systemPrompt());
    }
}
