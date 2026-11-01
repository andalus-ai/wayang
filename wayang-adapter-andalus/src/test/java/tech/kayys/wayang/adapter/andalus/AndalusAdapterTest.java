package tech.kayys.wayang.adapter.andalus;

import static org.junit.jupiter.api.Assertions.*;

import java.lang.reflect.Proxy;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.andalus.execution.ExecutionContext;
import tech.kayys.andalus.execution.VariableEntry;
import tech.kayys.andalus.execution.VariableKey;
import tech.kayys.andalus.execution.VariableStore;
import tech.kayys.andalus.plugin.spi.node.NodeDefinition;
import tech.kayys.andalus.service.ExecutionService;
import tech.kayys.wayang.agent.AgentSpec;

class AndalusAdapterTest {

    @SuppressWarnings("unchecked")
    private static <T> T proxy(Class<T> type, java.lang.reflect.InvocationHandler h) {
        return (T) Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[] { type }, h);
    }

    @Test
    void invokerRunsAgentByIdAndCollectsVariables() {
        Map<String, Object> seen = new LinkedHashMap<>();
        VariableStore store = proxy(VariableStore.class, (p, m, a) -> {
            if (m.getName().equals("entries")) {
                return Set.of(new VariableEntry<>(VariableKey.of("result", String.class), "42", null, "test"));
            }
            throw new UnsupportedOperationException(m.getName());
        });
        ExecutionContext ctx = proxy(ExecutionContext.class, (p, m, a) -> {
            if (m.getName().equals("variables")) {
                return store;
            }
            throw new UnsupportedOperationException(m.getName());
        });
        ExecutionService svc = proxy(ExecutionService.class, (p, m, a) -> {
            if (m.getName().equals("startAgent") && a[0] instanceof String) {
                seen.put("agent", a[0]);
                seen.putAll((Map<String, Object>) a[1]);
                return ctx;
            }
            throw new UnsupportedOperationException(m.getName());
        });
        AgentSpec spec = new AgentSpec("triage", null, "x/y", null, null, null, 7, 30, null, null, null);
        Map<String, Object> out = new AndalusAgentInvoker(svc).invoke(spec, Map.of("prompt", "hi"));
        assertEquals("triage", seen.get("agent"));
        assertEquals("hi", seen.get("prompt"));
        assertEquals(7, seen.get("wayang.maxSteps"));
        assertEquals("42", out.get("result"));
        assertEquals("42", out.get("output"), "result is aliased to output");
    }

    @Test
    void catalogHasUniqueTypesAndIsRegisteredForServiceLoader() throws Exception {
        List<NodeDefinition> nodes = new WayangNodeProvider().nodes();
        Set<String> types = new HashSet<>();
        for (NodeDefinition n : nodes) {
            assertTrue(types.add(n.type()), "duplicate " + n.type());
            assertTrue(n.type().startsWith("wayang."));
        }
        assertTrue(types.contains("wayang.dmn.decision") && types.contains("wayang.eip.pipeline"));
        try (var in = getClass().getClassLoader().getResourceAsStream(
                "META-INF/services/tech.kayys.andalus.plugin.spi.node.NodeProvider")) {
            assertNotNull(in, "service registration missing");
            assertTrue(new String(in.readAllBytes()).contains(WayangNodeProvider.class.getName()));
        }
    }
}
