package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.json.Json;

class ProcessMcpServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final EventBus bus = new EventBus();
    private final RunEngine engine = new ReferenceRunEngine(store, waits,
            Map.of("echo", in -> Map.of("said", String.valueOf(in.get("text"))), "boom", in -> {
                throw new IllegalStateException("backend down");
            }), (spec, in) -> Map.of(), bus, clock);
    private final WayangApi api = new WayangApi(store, engine, waits, bus, clock);
    private final ProcessMcpService svc = new ProcessMcpService(api, store, 10);

    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal operator = new Principal("acme", "ola", Set.of(Role.OPERATOR));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));
    private final Principal other = new Principal("globex", "gus", Set.of(Role.ADMIN));

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], kv[i + 1]);
        }
        return r;
    }

    private Map<String, Object> echo(Object studio) {
        Map<String, Object> d = m("id", "echo-it", "name", "Echo", "elements", List.of(m("id", "s", "type", "START_EVENT"),
                m("id", "c", "type", "SERVICE_TASK", "config", m("connector", "echo", "inputs", m("text", "text"))),
                m("id", "e", "type", "END_EVENT")),
                "flows", List.of(m("id", "f1", "source", "s", "target", "c"), m("id", "f2", "source", "c", "target", "e")));
        if (studio != null) {
            d.put("studio", studio);
        }
        return d;
    }

    private Map<String, Object> approval(Object studio) {
        Map<String, Object> d = m("id", "approve", "name", "Approve", "elements", List.of(m("id", "s", "type", "START_EVENT"),
                m("id", "u", "type", "USER_TASK", "config", m("title", "Approve")), m("id", "e", "type", "END_EVENT")),
                "flows", List.of(m("id", "f1", "source", "s", "target", "u"), m("id", "f2", "source", "u", "target", "e")));
        d.put("studio", studio);
        return d;
    }

    private void publish(Map<String, Object> doc) {
        String id = (String) doc.get("id");
        Long rev = store.getDraft("acme", DefinitionKind.PROCESS, id).map(StoredDefinition::revision).orElse(null);
        api.saveDraft(admin, DefinitionKind.PROCESS, id, doc, rev);
        api.publish(admin, DefinitionKind.PROCESS, (String) doc.get("id"));
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> call(Principal p, Object method, Map<String, Object> params) {
        String out = svc.rpc(p, Json.write(m("jsonrpc", "2.0", "id", 1, "method", method, "params", params)));
        return (Map<String, Object>) Json.read(out);
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> result(Map<String, Object> rpc) {
        return (Map<String, Object>) rpc.get("result");
    }

    private List<String> toolNames(Principal p) {
        List<String> n = new ArrayList<>();
        ((List<?>) result(call(p, "tools/list", Map.of())).get("tools")).forEach(t -> n.add((String) ((Map<?, ?>) t).get("name")));
        return n;
    }

    @Test
    void nothingIsExposedUnlessOptedIn() {
        publish(echo(null));
        publish(approval(m("mcp", m("expose", false))));
        assertEquals(List.of("get_run"), toolNames(admin));
        publish(echo(m("mcp", m("expose", true, "description", "Say it back"))));
        assertEquals(List.of("get_run", "run_echo-it"), toolNames(admin));
        assertEquals(List.of(), svc.list(other));
        assertEquals(List.of("get_run"), toolNames(other));
    }

    @Test
    void draftsAreNeverExposed() {
        api.saveDraft(admin, DefinitionKind.PROCESS, "echo-it", echo(m("mcp", m("expose", true))), null);
        assertEquals(List.of("get_run"), toolNames(admin));
    }

    @Test
    void callingAToolRunsTheProcessAndReturnsVariables() {
        publish(echo(m("mcp", m("expose", true))));
        var r = result(call(operator, "tools/call", m("name", "run_echo-it", "arguments", m("text", "hi"))));
        assertNotEquals(Boolean.TRUE, r.get("isError"));
        @SuppressWarnings("unchecked")
        Map<String, Object> sc = (Map<String, Object>) r.get("structuredContent");
        assertEquals("COMPLETED", sc.get("status"));
        assertEquals("hi", ((Map<?, ?>) sc.get("variables")).get("c.said"));
        var again = result(call(operator, "tools/call", m("name", "get_run", "arguments", m("runId", sc.get("runId")))));
        assertEquals("COMPLETED", ((Map<?, ?>) again.get("structuredContent")).get("status"));
    }

    @Test
    void argumentsAreCheckedAgainstTheDeclaredSchema() {
        publish(echo(m("mcp", m("expose", true, "inputSchema", m("type", "object", "required", List.of("text"),
                "properties", m("text", m("type", "string")), "additionalProperties", false)))));
        var bad = result(call(operator, "tools/call", m("name", "run_echo-it", "arguments", m("text", 5))));
        assertEquals(true, bad.get("isError"));
        var extra = result(call(operator, "tools/call", m("name", "run_echo-it", "arguments", m("text", "x", "evil", 1))));
        assertEquals(true, extra.get("isError"));
        assertTrue(engine.list("acme", "echo-it", 10).isEmpty(), "invalid calls must not start runs");
    }

    @Test
    void aRunParkedOnAHumanTaskReturnsItsIdAndWaitingStatus() {
        publish(approval(m("mcp", m("expose", true, "waitSeconds", 1))));
        var r = result(call(operator, "tools/call", m("name", "run_approve", "arguments", Map.of())));
        @SuppressWarnings("unchecked")
        Map<String, Object> sc = (Map<String, Object>) r.get("structuredContent");
        assertEquals("WAITING", sc.get("status"));
        assertNotNull(sc.get("waitingFor"));
        assertNotEquals(Boolean.TRUE, r.get("isError"));
    }

    @Test
    void failedRunsAreToolErrorsWithTheReason() {
        Map<String, Object> d = echo(m("mcp", m("expose", true)));
        d.put("id", "broken");
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) ((Map<String, Object>) ((List<?>) d.get("elements")).get(1)).get("config");
        cfg.put("connector", "boom");
        publish(d);
        var r = result(call(operator, "tools/call", m("name", "run_broken", "arguments", m("text", "x"))));
        assertEquals(true, r.get("isError"));
        String text = String.valueOf(((Map<?, ?>) ((List<?>) r.get("content")).get(0)).get("text"));
        assertTrue(text.contains("FAILED") && text.contains("backend down"), text);
    }

    @Test
    void permissionsAreEnforced() {
        publish(echo(m("mcp", m("expose", true))));
        // viewer can list but not run
        assertTrue(toolNames(viewer).contains("run_echo-it"));
        var denied = result(call(viewer, "tools/call", m("name", "run_echo-it", "arguments", m("text", "x"))));
        assertEquals(true, denied.get("isError"));
        assertTrue(engine.list("acme", "echo-it", 10).isEmpty());
        assertThrows(ApiException.class, () -> svc.rpc(new Principal("acme", "n", Set.of()), "{}"));
        assertThrows(ApiException.class, () -> svc.rpc(null, "{}"));
    }

    @Test
    void callerWithoutRunReadGetsNoVariables() {
        publish(echo(m("mcp", m("expose", true))));
        Principal starter = new Principal("acme", "st", Set.of(), Set.of(Permission.DEFINITION_READ, Permission.RUN_START));
        var r = result(call(starter, "tools/call", m("name", "run_echo-it", "arguments", m("text", "secret-ish"))));
        @SuppressWarnings("unchecked")
        Map<String, Object> sc = (Map<String, Object>) r.get("structuredContent");
        assertNull(sc.get("variables"));
        assertEquals("COMPLETED", sc.get("status"));
    }

    @Test
    void tenantsCannotReadEachOthersRuns() {
        publish(echo(m("mcp", m("expose", true))));
        var r = result(call(operator, "tools/call", m("name", "run_echo-it", "arguments", m("text", "x"))));
        String id = (String) ((Map<?, ?>) r.get("structuredContent")).get("runId");
        var leak = result(call(other, "tools/call", m("name", "get_run", "arguments", m("runId", id))));
        assertEquals(true, leak.get("isError"));
    }

    @Test
    void studioMcpKeyIsValidatedStrictly() {
        for (Object bad : List.of(m("expose", "yes"), m("expose", true, "waitSeconds", 61), m("waitSeconds", 1.5),
                m("description", "x".repeat(1001)), m("inputSchema", "x"), m("bogus", 1), "nope")) {
            assertThrows(ApiException.class, () -> api.saveDraft(admin, DefinitionKind.PROCESS, "echo-it", echo(m("mcp", bad)), null), String.valueOf(bad));
        }
    }
}
