package tech.kayys.wayang.embed;

import static org.junit.jupiter.api.Assertions.*;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ServiceHandler;

class AgentActionsTest {

    private final List<Map<String, Object>> configs = new ArrayList<>();
    private final List<Map<String, Object>> inputs = new ArrayList<>();

    private final Map<String, ServiceHandler.Connector> connectors = Map.of("gcal", new ServiceHandler.Connector() {
        @Override
        public Map<String, Object> call(Map<String, Object> in) {
            return call(Map.of(), in);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) {
            configs.add(config);
            inputs.add(in);
            if ("boom".equals(in.get("summary"))) {
                throw new IllegalStateException("rejected token-abcdef");
            }
            return Map.of("id", "ev1");
        }
    });

    private final AgentActions actions = new AgentActions(connectors, (t, n) -> n.equals("oauth.google") ? "token-abcdef" : null, (t, p, n) -> "cfg-" + n);

    private static Map<String, Object> call(String tool, String type, Map<String, Object> config, Map<String, Object> args) {
        return Map.of("tool", tool, "type", type, "config", config, "args", args);
    }

    @SuppressWarnings("unchecked")
    private static List<Map<String, Object>> results(Map<String, Object> out) {
        return (List<Map<String, Object>>) out.get("results");
    }

    @Test
    void missingOrEmptyCallsDoNothing() {
        assertEquals(0, actions.call(Map.of()).get("done"));
        assertEquals(0, actions.call(java.util.Collections.singletonMap("calls", null)).get("done"));
        assertEquals(0, actions.call(Map.of("calls", List.of())).get("done"));
        assertTrue(configs.isEmpty());
    }

    @Test
    void anApprovedCallRunsWithItsReferencesLookedUpNow() {
        var out = actions.call(Map.of("calls", List.of(call("calendar_create", "gcal",
                Map.of("operation", "create", "accessToken", "${secret:oauth.google}", "calendar", "${config:CAL}"), Map.of("summary", "Lunch")))));
        assertEquals(1, out.get("done"));
        assertEquals(0, out.get("failed"));
        assertEquals("token-abcdef", configs.get(0).get("accessToken"));
        assertEquals("cfg-CAL", configs.get(0).get("calendar"));
        assertEquals(Map.of("summary", "Lunch"), inputs.get(0));
        assertEquals(true, results(out).get(0).get("ok"));
        assertFalse(String.valueOf(out).contains("token-abcdef"), "the result carries no secret value");
    }

    @Test
    void aFailureIsReportedWithoutSecretsAndDoesNotStopTheOthers() {
        Map<String, Object> cfg = Map.of("accessToken", "${secret:oauth.google}");
        var out = actions.call(Map.of("calls", List.of(call("a", "gcal", cfg, Map.of("summary", "boom")), call("b", "gcal", cfg, Map.of("summary", "ok")),
                call("c", "gcal", Map.of("accessToken", "${secret:missing}"), Map.of()))));
        assertEquals(1, out.get("done"));
        assertEquals(2, out.get("failed"));
        assertEquals("rejected ***", results(out).get(0).get("error"));
        assertEquals(true, results(out).get(1).get("ok"));
        assertTrue(String.valueOf(results(out).get(2).get("error")).contains("'missing' is not set"));
        assertFalse(String.valueOf(out).contains("token-abcdef"));
    }

    @Test
    void onlyTheConnectorsOfTheReleaseAndNeverItselfAndAtMostTenCalls() {
        var out = actions.call(Map.of("calls", List.of(call("x", "nosuch", Map.of(), Map.of()), call("y", AgentActions.NAME, Map.of(), Map.of()), "junk",
                Map.of("tool", "z", "type", "gcal", "args", "notamap", "config", Map.of()))));
        assertEquals(0, out.get("done"));
        assertEquals(4, out.get("failed"));
        assertTrue(String.valueOf(results(out).get(0).get("error")).contains("no connector 'nosuch'"));
        assertTrue(String.valueOf(results(out).get(1).get("error")).contains("no connector"));
        assertTrue(configs.isEmpty());
        List<Object> many = new ArrayList<>();
        for (int i = 0; i < 11; i++) {
            many.add(call("t", "gcal", Map.of(), Map.of()));
        }
        assertThrows(IllegalArgumentException.class, () -> actions.call(Map.of("calls", many)));
        assertThrows(IllegalArgumentException.class, () -> actions.call(Map.of("calls", "nope")));
    }

    @Test
    void theRuntimeOffersItToProcesses() {
        var all = AgentActions.with(connectors, null, null);
        assertTrue(all.containsKey("agent-actions") && all.containsKey("gcal"));
    }
}
