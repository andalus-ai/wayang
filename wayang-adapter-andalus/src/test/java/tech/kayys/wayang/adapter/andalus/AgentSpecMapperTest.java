package tech.kayys.wayang.adapter.andalus;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.core.AgentDefinition;
import tech.kayys.andalus.execution.GraphExecutionContext;
import tech.kayys.andalus.execution.VariableStore;
import tech.kayys.andalus.service.ExecutionService;
import tech.kayys.wayang.agent.AgentSpec;

class AgentSpecMapperTest {

    private static AgentSpec spec(String id, Map<String, Object> options) {
        return new AgentSpec(id, "Concierge", "openai/gpt-4o", "Help {{ who }}.", null,
                List.of(new AgentSpec.Tool("lookup", "http", null, Map.of()), new AgentSpec.Tool("refund", "http", null, Map.of())),
                9, 120, List.of("refund"), null, options);
    }

    @Test
    void mapsIdentityPromptRoleModelToolsAndConstraints() {
        var options = Map.<String, Object>of(
                "persona", Map.of("role", "Support", "tone", "calm"),
                "guardrails", Map.of("redact", List.of("email")));
        AgentDefinition d = AgentSpecMapper.toDefinition(spec("support.triage", options), null);
        assertEquals("support.triage", d.identity().id());
        assertEquals("Concierge", d.identity().name());
        assertEquals("Support", d.role());
        assertEquals("Role: Support\nTone: calm\n\nHelp {{ who }}.", d.goal());
        assertEquals(d.goal(), d.instructions());
        assertEquals("openai/gpt-4o", d.model().name());
        assertTrue(d.hasTool("lookup") && d.hasTool("refund"));
        assertEquals(9, d.constraints().get("maxSteps"));
        assertEquals(120, d.constraints().get("timeoutSeconds"));
        assertEquals(List.of("refund"), d.constraints().get("requireApproval"));
        assertEquals(Map.of("redact", List.of("email")), d.constraints().get("guardrails"));
    }

    @Test
    void renderedPromptWinsAndNoPersonaOrApprovalLeavesThemOut() {
        AgentSpec s = new AgentSpec("a", null, "m", "raw", null, null, 5, 60, null, null, null);
        AgentDefinition d = AgentSpecMapper.toDefinition(s, "Rendered for Ana");
        assertEquals("Rendered for Ana", d.goal());
        assertEquals("", d.role());
        assertFalse(d.constraints().containsKey("requireApproval"));
        assertFalse(d.constraints().containsKey("guardrails"));
        assertEquals("raw", AgentSpecMapper.toDefinition(s, "  ").goal());
    }

    @Test
    void invalidAndalusIdIsExplainedWithoutBuildingAnything() {
        var e = assertThrows(IllegalArgumentException.class, () -> AgentSpecMapper.toDefinition(spec("bad id!", Map.of()), null));
        assertTrue(e.getMessage().contains("bad id!"));
    }

    @Test
    void invokerByDefinitionPassesTheRenderedPromptAndNeverLooksUpById() {
        var def = new AtomicReference<AgentDefinition>();
        var byId = new AtomicReference<String>();
        GraphExecutionContext ctx = proxy(GraphExecutionContext.class, (p, m, a) -> {
            if (m.getName().equals("variables")) {
                return proxy(VariableStore.class, (p2, m2, a2) -> Set.of());
            }
            throw new UnsupportedOperationException(m.getName());
        });
        ExecutionService svc = proxy(ExecutionService.class, (p, m, a) -> {
            if (m.getName().equals("startAgent") && a[0] instanceof AgentDefinition d) {
                def.set(d);
                return ctx;
            }
            if (m.getName().equals("startAgent")) {
                byId.set(String.valueOf(a[0]));
                return ctx;
            }
            throw new UnsupportedOperationException(m.getName());
        });
        AndalusAgentInvoker.byDefinition(svc).invoke(spec("a", Map.of()), Map.of("system", "Help Ana."));
        assertNotNull(def.get());
        assertEquals("Help Ana.", def.get().goal());
        assertNull(byId.get());
    }

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, h);
    }
}
