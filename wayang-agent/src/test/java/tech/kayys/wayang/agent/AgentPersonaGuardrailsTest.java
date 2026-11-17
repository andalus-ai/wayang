package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AgentPersonaGuardrailsTest {

    private static AgentSpec spec(Map<String, Object> options) {
        return new AgentSpec("a", null, "m", "Help with {{ topic }}.", null, null, 5, 60, null, null, options);
    }

    @Test
    void personaComposesBeforeThePromptAndIsLiteral() {
        var p = AgentPersona.of(spec(Map.of("persona", Map.of("role", "Support agent", "tone", "calm", "language", "Indonesian",
                "boundaries", List.of("Never promise refunds", "{{ secret }}")))));
        String s = p.compose("Help with billing.");
        assertEquals("Role: Support agent\nTone: calm\nReply in: Indonesian\nBoundaries (never do these):\n- Never promise refunds\n- {{ secret }}\n\nHelp with billing.", s);
        assertEquals("Role: x", new AgentPersona("x", null, null, List.of()).compose(""));
    }

    @Test
    void noPersonaOrGuardrailsMeansNull() {
        assertNull(AgentPersona.of(spec(Map.of())));
        assertNull(AgentGuardrails.of(spec(Map.of())));
    }

    @Test
    void malformedPersonaIsADefinitionError() {
        for (Object bad : List.of("text", Map.of(), Map.of("role", 5), Map.of("rolle", "x"), Map.of("role", "x".repeat(201)),
                Map.of("boundaries", "no"), Map.of("boundaries", List.of(" ")), Map.of("boundaries", java.util.Collections.nCopies(21, "x")))) {
            assertThrows(AgentSpecException.class, () -> spec(Map.of("persona", bad)), String.valueOf(bad));
        }
    }

    @Test
    void malformedGuardrailsAreADefinitionError() {
        for (Object bad : List.of("x", Map.of(), Map.of("maxOutputChars", 0), Map.of("maxOutputChars", 100001), Map.of("maxOutputChars", "9"),
                Map.of("redact", List.of("ssn")), Map.of("redact", "email"), Map.of("blockedTerms", List.of("")),
                Map.of("blockedTerms", java.util.Collections.nCopies(51, "x")), Map.of("other", 1))) {
            assertThrows(AgentSpecException.class, () -> spec(Map.of("guardrails", bad)), String.valueOf(bad));
        }
    }

    @Test
    void redactsEmailPhoneAndLuhnValidCardsOnly() {
        var g = AgentGuardrails.of(spec(Map.of("guardrails", Map.of("redact", List.of("email", "phone", "card")))));
        assertEquals("Mail [email removed] or call [phone removed].", g.apply("Mail ana.b@example.co.id or call +62 812-3456-7890."));
        assertEquals("Card [card removed] ok", g.apply("Card 4111 1111 1111 1111 ok"));
        // not Luhn-valid, so not a card; a long digit run still looks like a phone number (documented over-matching)
        assertEquals("Order [phone removed] shipped", g.apply("Order 1234567890123 shipped"));
        assertEquals("Ref 12345 ok", g.apply("Ref 12345 ok"));
    }

    @Test
    void blockedTermReplacesTheWholeValueCaseInsensitively() {
        var g = AgentGuardrails.of(spec(Map.of("guardrails", Map.of("blockedTerms", List.of("Internal Use Only")))));
        assertEquals(AgentGuardrails.BLOCKED, g.apply("note: internal use only!"));
        assertEquals("fine", g.apply("fine"));
    }

    @Test
    void lengthCapAppliesAfterRedactionAndOnlyTextValuesAreTouched() {
        var g = AgentGuardrails.of(spec(Map.of("guardrails", Map.of("maxOutputChars", 5, "redact", List.of("email")))));
        Map<String, Object> out = g.applyTo(Map.of("output", "abcdefghij", "mail", "a@b.co", "n", 12345678));
        assertEquals("abcde…", out.get("output"));
        assertEquals("[emai…", out.get("mail"));
        assertEquals(12345678, out.get("n"));
    }

    @Test
    void templatedAgentsCanCarryThem() {
        var t = new AgentTemplate(Map.of("id", "a-${lang}", "model", "m",
                "options", Map.of("persona", Map.of("language", "${lang}"))), Map.of());
        assertEquals("Indonesian", AgentPersona.of(t.instantiate(Map.of("lang", "Indonesian"))).language());
    }
}
