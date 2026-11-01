package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.Map;

import org.junit.jupiter.api.Test;

class AgentTest {

    private static final String YAML = """
            id: support-triage
            model: openai/gpt-4o
            systemPrompt: "You triage tickets for {{ company }}."
            inputTemplate: "Ticket: {{ ticket.text }}"
            maxSteps: 5
            tools:
              - name: lookup
                type: http
                config: {url: "https://example.test/lookup"}
              - name: refund
                type: http
            requireApproval: [refund]
            outputKeys: [category]
            """;

    @Test
    void loadsYaml() {
        AgentSpec s = AgentSpecLoader.fromYaml(YAML);
        assertEquals("support-triage", s.id());
        assertEquals(5, s.maxSteps());
        assertEquals(120, s.timeoutSeconds());
        assertEquals(2, s.tools().size());
        assertEquals(java.util.List.of("refund"), s.requireApproval());
    }

    @Test
    void rejectsBadSpecs() {
        assertThrows(AgentSpecException.class, () -> AgentSpecLoader.fromYaml("id: a\nmodel: m\nmaxSteps: 1000"));
        assertThrows(AgentSpecException.class, () -> AgentSpecLoader.fromYaml("id: a b\nmodel: m"));
        assertThrows(AgentSpecException.class, () -> AgentSpecLoader.fromYaml("id: a\nmodel: m\nrequireApproval: [ghost]"));
        assertThrows(AgentSpecException.class, () -> AgentSpecLoader.fromYaml("id: a"));
        assertThrows(AgentSpecException.class, () -> AgentSpecLoader.fromYaml("- just\n- a list"));
    }

    @Test
    void safeYamlRefusesObjectConstruction() {
        assertThrows(AgentSpecException.class,
                () -> AgentSpecLoader.fromYaml("id: !!java.io.File [\"/etc/passwd\"]\nmodel: m"));
    }

    @Test
    void rendersTemplates() {
        assertEquals("Ticket: printer on fire", PromptTemplate.render("Ticket: {{ ticket.text }}",
                Map.of("ticket", Map.of("text", "printer on fire"))));
        assertEquals("Hello !", PromptTemplate.render("Hello {{ missing }}!", Map.of()));
        assertEquals("2 items", PromptTemplate.render("{{ count(xs) }} items", Map.of("xs", java.util.List.of(1, 2))));
        assertThrows(AgentSpecException.class, () -> PromptTemplate.render("{{ oops", Map.of()));
        assertThrows(AgentSpecException.class, () -> PromptTemplate.render("{{ 1 + }}", Map.of()));
    }
}
