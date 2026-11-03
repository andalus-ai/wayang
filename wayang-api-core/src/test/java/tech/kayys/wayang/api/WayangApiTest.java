package tech.kayys.wayang.api;

import tech.kayys.wayang.api.definition.AutoLayout;
import tech.kayys.wayang.api.run.CollectSweepService;
import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.project.Project;
import tech.kayys.wayang.api.project.ProjectStore;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.api.run.RunEngine;
import tech.kayys.wayang.api.run.RunEvent;
import tech.kayys.wayang.api.run.RunStatus;
import tech.kayys.wayang.api.run.RunView;
import tech.kayys.wayang.api.definition.StoredDefinition;
import tech.kayys.wayang.api.definition.Studio;
import tech.kayys.wayang.api.run.TaskView;
import tech.kayys.wayang.api.run.WsSession;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.json.Json;

class WayangApiTest {

    private final Instant t0 = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = Clock.fixed(t0, ZoneOffset.UTC);
    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final EventBus bus = new EventBus();
    private final tech.kayys.wayang.eip.AggregationStore aggregation = new tech.kayys.wayang.eip.AggregationStore.InMemory(1000);
    private final RunEngine engine = new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, clock)
            .withEip(new tech.kayys.wayang.eip.IdempotencyStore.InMemory(1000), new tech.kayys.wayang.eip.ClaimStore.InMemory(1000), aggregation);
    private final WayangApi api = new WayangApi(store, engine, waits, bus, clock);

    private final Principal acme = new Principal("acme", "ann", java.util.Set.of(Role.ADMIN));
    private final Principal globex = new Principal("globex", "gus", java.util.Set.of(Role.ADMIN));
    private final Principal viewer = new Principal("acme", "vic", java.util.Set.of(Role.VIEWER));

    private static Map<String, Object> decision() {
        return Map.of("id", "risk", "decisions", List.of(Map.of("name", "level", "type", "table", "hitPolicy", "FIRST",
                "inputs", List.of("amount"), "outputs", List.of("level"),
                "rules", List.of(Map.of("when", List.of(">= 1000"), "then", List.of("\"high\"")),
                        Map.of("when", List.of("-"), "then", List.of("\"low\""))))));
    }

    private static Map<String, Object> process(boolean withTimer) {
        List<Object> els = new ArrayList<>(List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "rule", "type", "BUSINESS_RULE_TASK", "config", Map.of("model", "risk")),
                Map.of("id", "ok", "type", "USER_TASK", "config", Map.of("title", "Approve")),
                Map.of("id", "e", "type", "END_EVENT")));
        return Map.of("id", "loan", "name", "Loan", "elements", els, "flows", List.of(
                Map.of("id", "f1", "source", "s", "target", "rule"), Map.of("id", "f2", "source", "rule", "target", "ok"),
                Map.of("id", "f3", "source", "ok", "target", "e")));
    }

    private void deploy(Principal p) {
        api.saveDraft(p, DefinitionKind.DECISION, "risk", decision(), null);
        api.publish(p, DefinitionKind.DECISION, "risk");
        api.saveDraft(p, DefinitionKind.PROCESS, "loan", process(false), null);
        api.publish(p, DefinitionKind.PROCESS, "loan");
    }

    @Test
    void fullLifecycleWithTaskAndEvents() throws Exception {
        deploy(acme);
        List<RunEvent> seen = new ArrayList<>();
        try (AutoCloseable sub = api.subscribe(acme, null, seen::add)) {
            RunView run = api.startRun(acme, "loan", null, Map.of("amount", 5000));
            assertEquals(RunStatus.WAITING, run.status());
            assertEquals("high", run.variables().get("rule.level"));
            List<TaskView> tasks = api.listTasks(acme, 50);
            assertEquals(1, tasks.size());
            assertEquals("Approve", tasks.get(0).title());
            api.completeTask(acme, tasks.get(0).token(), Map.of("approved", true));
            assertEquals(RunStatus.COMPLETED, api.getRun(acme, run.id()).status());
            assertTrue(api.listTasks(acme, 50).isEmpty());
            assertThrows(ApiException.class, () -> api.completeTask(acme, tasks.get(0).token(), Map.of()));
        }
        var types = seen.stream().map(RunEvent::type).toList();
        assertTrue(types.contains(RunEvent.Type.RUN_STARTED));
        assertTrue(types.contains(RunEvent.Type.TASK_CREATED));
        assertTrue(types.contains(RunEvent.Type.TASK_COMPLETED));
        assertTrue(types.contains(RunEvent.Type.RUN_COMPLETED));
    }

    @Test
    void tenantsAreIsolated() {
        deploy(acme);
        RunView run = api.startRun(acme, "loan", null, Map.of("amount", 1));
        String token = api.listTasks(acme, 10).get(0).token();
        assertEquals(404, assertThrows(ApiException.class, () -> api.getRun(globex, run.id())).code().httpStatus());
        assertTrue(api.listTasks(globex, 10).isEmpty());
        assertThrows(ApiException.class, () -> api.completeTask(globex, token, Map.of()));
        assertThrows(ApiException.class, () -> api.cancelRun(globex, run.id()));
        assertThrows(ApiException.class, () -> api.subscribe(globex, run.id(), e -> {
        }));
        assertThrows(ApiException.class, () -> api.startRun(globex, "loan", null, Map.of()));
        assertTrue(api.list(globex, DefinitionKind.PROCESS).isEmpty());
        assertEquals(RunStatus.WAITING, api.getRun(acme, run.id()).status());
    }

    @Test
    void rolesAreEnforced() {
        assertEquals(403, assertThrows(ApiException.class,
                () -> api.saveDraft(viewer, DefinitionKind.DECISION, "d", decision(), null)).code().httpStatus());
        deploy(acme);
        assertThrows(ApiException.class, () -> api.startRun(viewer, "loan", null, Map.of()));
        assertThrows(ApiException.class, () -> api.fireTimers(viewer));
        assertEquals(401, assertThrows(ApiException.class, () -> api.getRun(null, "x")).code().httpStatus());
        assertNotNull(api.list(viewer, DefinitionKind.PROCESS));
    }

    @Test
    void draftEditsUseOptimisticConcurrency() {
        StoredDefinition d1 = api.saveDraft(acme, DefinitionKind.DECISION, "risk", decision(), null);
        assertEquals(1, d1.revision());
        StoredDefinition d2 = api.saveDraft(acme, DefinitionKind.DECISION, "risk", decision(), 1L);
        assertEquals(2, d2.revision());
        ApiException stale = assertThrows(ApiException.class,
                () -> api.saveDraft(acme, DefinitionKind.DECISION, "risk", decision(), 1L));
        assertEquals(409, stale.code().httpStatus());
        assertThrows(ApiException.class, () -> api.saveDraft(acme, DefinitionKind.DECISION, "risk", decision(), null));
    }

    @Test
    void publishIsImmutableAndVersioned() {
        api.saveDraft(acme, DefinitionKind.DECISION, "risk", decision(), null);
        assertEquals(1, api.publish(acme, DefinitionKind.DECISION, "risk").version());
        assertEquals(2, api.publish(acme, DefinitionKind.DECISION, "risk").version());
        assertEquals(2, api.getVersion(acme, DefinitionKind.DECISION, "risk", null).version());
        assertEquals(1, api.getVersion(acme, DefinitionKind.DECISION, "risk", 1).version());
    }

    @Test
    void publishRejectsInvalidAndDanglingReferences() {
        api.saveDraft(acme, DefinitionKind.PROCESS, "loan", process(false), null); // decision 'risk' not published
        ApiException e = assertThrows(ApiException.class, () -> api.publish(acme, DefinitionKind.PROCESS, "loan"));
        assertEquals(422, e.code().httpStatus());
        assertTrue(e.details().stream().anyMatch(s -> s.contains("risk")));
        api.saveDraft(acme, DefinitionKind.AGENT, "bad", Map.of("id", "bad"), null);
        assertThrows(ApiException.class, () -> api.publish(acme, DefinitionKind.AGENT, "bad"));
        assertFalse(api.validate(acme, DefinitionKind.PROCESS, Map.of("id", "x", "elements", List.of())).valid());
    }

    @Test
    void idMismatchAndBadIdsAreRejected() {
        assertThrows(ApiException.class, () -> api.saveDraft(acme, DefinitionKind.DECISION, "other", decision(), null));
        assertThrows(ApiException.class, () -> api.saveDraft(acme, DefinitionKind.DECISION, "../x", Map.of(), null));
    }

    @Test
    void cancelParkedRun() {
        deploy(acme);
        RunView run = api.startRun(acme, "loan", null, Map.of("amount", 1));
        api.cancelRun(acme, run.id());
        assertEquals(RunStatus.CANCELLED, api.getRun(acme, run.id()).status());
        assertTrue(api.listTasks(acme, 10).isEmpty());
        assertEquals(409, assertThrows(ApiException.class, () -> api.cancelRun(acme, run.id())).code().httpStatus());
    }

    @Test
    void timersAndMessagesComplete() {
        api.saveDraft(acme, DefinitionKind.PROCESS, "w", Map.of("id", "w", "elements", List.of(
                Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "t", "type", "TIMER_EVENT", "config", Map.of("duration", "PT0S")),
                Map.of("id", "m", "type", "MESSAGE_CATCH_EVENT", "config", Map.of("message", "paid", "correlation", "\"k1\"")),
                Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "a", "source", "s", "target", "t"), Map.of("id", "b", "source", "t", "target", "m"),
                        Map.of("id", "c", "source", "m", "target", "e"))), null);
        api.publish(acme, DefinitionKind.PROCESS, "w");
        RunView run = api.startRun(acme, "w", null, Map.of());
        assertEquals(RunStatus.WAITING, run.status());
        assertEquals(1, api.fireTimers(acme));
        assertEquals("paid", api.getRun(acme, run.id()).waiting().get(0).messageName());
        assertEquals("BUFFERED", api.sendMessage(globex, "paid", "k1", Map.of())); // other tenant must not complete it
        assertEquals(RunStatus.WAITING, api.getRun(acme, run.id()).status());
    }

    @Test
    void authoringAids() {
        assertEquals(new java.math.BigDecimal("3"), api.evalExpression(acme, "1 + 2", Map.of()));
        assertThrows(ApiException.class, () -> api.evalExpression(acme, "1 +", Map.of()));
        assertEquals("high", api.testDecision(acme, decision(), Map.of("amount", 2000)).get("level"));
    }

    @Test
    void bpmnImportExportRoundTrip() {
        api.saveDraft(acme, DefinitionKind.DECISION, "risk", decision(), null);
        api.saveDraft(acme, DefinitionKind.PROCESS, "loan", process(false), null);
        String xml = api.exportBpmn(acme, "loan", null);
        Map<String, Object> back = api.importBpmn(acme, xml);
        assertEquals(4, ((List<?>) back.get("elements")).size());
        assertThrows(ApiException.class, () -> api.importBpmn(acme, "<not xml"));
    }

    @Test
    void wsSessionSubscribesAndReceivesOnlyOwnTenantEvents() {
        deploy(acme);
        deploy(globex);
        List<String> out = new ArrayList<>();
        try (WsSession ws = new WsSession(api, acme, out::add, clock)) {
            ws.onText("{\"id\":\"1\",\"op\":\"subscribe\"}");
            api.startRun(globex, "loan", null, Map.of("amount", 1));
            api.startRun(acme, "loan", null, Map.of("amount", 1));
            ws.onText("{\"id\":\"2\",\"op\":\"nope\"}");
            ws.onText("not json");
            ws.onText("{\"id\":\"3\",\"op\":\"subscribe\",\"runId\":\"does-not-exist\"}");
            assertTrue(out.get(0).contains("\"ok\":true"));
            long events = out.stream().filter(s -> s.contains("\"event\"")).count();
            assertTrue(events >= 3, out.toString());
            assertTrue(out.stream().noneMatch(s -> s.contains("globex")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"id\":\"2\"") && s.contains("\"ok\":false")));
            assertTrue(out.stream().anyMatch(s -> s.contains("\"id\":\"3\"") && s.contains("not found")));
            assertEquals(1, ws.subscriptionCount());
            Object ev = Json.read(out.stream().filter(s -> s.contains("\"event\"")).findFirst().get());
            assertTrue(ev instanceof Map);
        }
        assertEquals(0, bus.subscriberCount());
    }

    @Test
    void slowConsumerGetsOverflowMarker() {
        var buf = new EventBus.BoundedBuffer(2);
        for (int i = 0; i < 5; i++) {
            buf.offer(new RunEvent(RunEvent.Type.NODE_COMPLETED, "acme", "r", "n" + i, t0, Map.of()));
        }
        var drained = buf.drain("acme", t0);
        assertEquals(RunEvent.Type.OVERFLOW, drained.get(0).type());
        assertEquals(3L, drained.get(0).data().get("dropped"));
        assertEquals(3, drained.size());
    }

    @Test
    void viewsFlattenToJsonSafeValues() {
        deploy(acme);
        RunView run = api.startRun(acme, "loan", null, Map.of("amount", 5000));
        Object plain = Views.plain(run);
        String json = Json.write(plain);
        Map<?, ?> back = (Map<?, ?>) Json.read(json);
        assertEquals("WAITING", back.get("status"));
        assertEquals("loan", back.get("processId"));
        assertTrue(((List<?>) back.get("waiting")).get(0) instanceof Map);
        assertEquals(t0.toString(), back.get("startedAt"));
    }

    @Test
    void inMemoryStoreSatisfiesContract() {
        tech.kayys.wayang.api.testkit.DefinitionStoreContract.verify(DefinitionStore.InMemory::new);
    }

    @Test
    void projectsGroupDefinitionsAndStayTenantScoped() {
        Project proj = api.createProject(acme, "Lending", "loan flows");
        assertThrows(ApiException.class, () -> api.getProject(globex, proj.id()));
        assertTrue(api.listProjects(globex).isEmpty());
        assertThrows(ApiException.class, () -> api.createProject(viewer, "x", null));
        assertThrows(ApiException.class, () -> api.createProject(acme, " ", null));
        // a definition may only join an existing project of the same tenant
        Map<String, Object> doc = new java.util.LinkedHashMap<>(decision());
        doc.put("studio", Map.of("projectId", "nope"));
        assertThrows(ApiException.class, () -> api.saveDraft(acme, DefinitionKind.DECISION, "risk", doc, null));
        doc.put("studio", Map.of("projectId", proj.id(), "layout", Map.of("a", Map.of("x", 10, "y", 20)), "tags", List.of("t")));
        api.saveDraft(acme, DefinitionKind.DECISION, "risk", doc, null);
        assertEquals(1, api.list(acme, DefinitionKind.DECISION, proj.id()).size());
        assertEquals(0, api.list(acme, DefinitionKind.DECISION, "other").size());
        assertEquals(409, assertThrows(ApiException.class, () -> api.deleteProject(acme, proj.id())).code().httpStatus());
        // studio metadata never reaches the compiler: publish and run still work
        api.publish(acme, DefinitionKind.DECISION, "risk");
        assertEquals("high", api.testDecision(acme, Studio.executable(doc), Map.of("amount", 5000)).get("level"));
        api.deleteDraft(acme, DefinitionKind.DECISION, "risk");
        assertEquals(1, api.list(acme, DefinitionKind.DECISION, proj.id()).size(), "published version still belongs to the project");
        assertEquals("Lending2", api.updateProject(acme, proj.id(), "Lending2", null).name());
    }

    @Test
    void studioMetadataIsValidatedAndStripped() {
        for (Object bad : List.of("str", Map.of("evil", 1), Map.of("projectId", "../x"), Map.of("tags", List.of(1)),
                Map.of("layout", Map.of("a", Map.of("x", 1))), Map.of("layout", Map.of("a", Map.of("x", 1e12, "y", 0))))) {
            Map<String, Object> d = new java.util.LinkedHashMap<>(decision());
            d.put("studio", bad);
            assertThrows(ApiException.class, () -> api.validate(acme, DefinitionKind.DECISION, d), String.valueOf(bad));
        }
        Map<String, Object> ok = new java.util.LinkedHashMap<>(process(false));
        ok.put("studio", Map.of("layout", Map.of("s", Map.of("x", 1, "y", 2))));
        api.saveDraft(acme, DefinitionKind.DECISION, "risk", decision(), null);
        api.publish(acme, DefinitionKind.DECISION, "risk");
        assertTrue(api.validate(acme, DefinitionKind.PROCESS, ok).valid(), "layout must not make a process invalid");
        assertFalse(Studio.executable(ok).containsKey("studio"));
    }

    @Test
    void importedBpmnComesWithAutoLayout() {
        api.saveDraft(acme, DefinitionKind.DECISION, "risk", decision(), null);
        api.saveDraft(acme, DefinitionKind.PROCESS, "loan", process(false), null);
        Map<String, Object> imported = api.importBpmn(acme, api.exportBpmn(acme, "loan", null));
        @SuppressWarnings("unchecked")
        Map<String, Map<String, Number>> layout = (Map<String, Map<String, Number>>) Studio.of(imported).get("layout");
        assertEquals(4, layout.size());
        assertTrue(layout.get("s").get("x").doubleValue() < layout.get("rule").get("x").doubleValue());
        assertTrue(layout.get("rule").get("x").doubleValue() < layout.get("ok").get("x").doubleValue());
        assertTrue(layout.get("ok").get("x").doubleValue() < layout.get("e").get("x").doubleValue());
    }

    @Test
    void autoLayoutRanksByLongestPathAndSurvivesCycles() {
        var m = tech.kayys.wayang.bpmn.ProcessMaps.fromMap(Map.of("id", "x", "elements", List.of(
                Map.of("id", "s", "type", "START_EVENT"), Map.of("id", "a", "type", "SERVICE_TASK"),
                Map.of("id", "b", "type", "SERVICE_TASK"), Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "1", "source", "s", "target", "a"), Map.of("id", "2", "source", "s", "target", "b"),
                        Map.of("id", "3", "source", "a", "target", "e"), Map.of("id", "4", "source", "b", "target", "e"),
                        Map.of("id", "5", "source", "e", "target", "s"))));
        var pos = AutoLayout.layered(m);
        assertEquals(pos.get("a")[0], pos.get("b")[0]);
        assertNotEquals(pos.get("a")[1], pos.get("b")[1]);
        assertEquals(4, pos.size());
    }

    @Test
    void inMemoryProjectStoreSatisfiesContract() {
        tech.kayys.wayang.api.testkit.ProjectStoreContract.verify(ProjectStore.InMemory::new);
    }

    @Test
    void configSchemaErrorsAppearInValidationWithElementIds() {
        Map<String, Object> bad = Map.of("id", "p", "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "t", "type", "SERVICE_TASK", "config", Map.of("retries", 99)),
                Map.of("id", "e", "type", "END_EVENT")), "flows", List.of(Map.of("id", "a", "source", "s", "target", "t"),
                Map.of("id", "b", "source", "t", "target", "e")));
        var r = api.validate(acme, DefinitionKind.PROCESS, bad);
        assertFalse(r.valid());
        assertTrue(r.items().stream().anyMatch(i -> "t".equals(i.elementId()) && i.message().contains("connector")));
        assertEquals(tech.kayys.wayang.bpmn.ElementType.values().length, api.nodeCatalog(acme).size());
        assertNotNull(api.nodeCatalog(viewer));
    }

    @Test
    void aProfileHidesAndRefusesTheFeaturesItDoesNotServe() {
        Map<String, Object> doc = Map.of("id", "p", "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "a", "type", "AGENT_TASK", "config", Map.of("agent", "helper")), Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "a"), Map.of("id", "f2", "source", "a", "target", "e")));
        var restricted = new WayangApi(store, engine, waits, bus, clock).withProfile(tech.kayys.wayang.runtime.Profile.AUTOMATION);
        var r = restricted.validate(acme, DefinitionKind.PROCESS, doc);
        assertTrue(r.items().stream().anyMatch(i -> "a".equals(i.elementId()) && i.message().contains("'automation' profile")), String.valueOf(r.items()));
        assertFalse(r.valid());
        var types = restricted.nodeCatalog(acme).stream().map(m -> m.get("type")).toList();
        assertFalse(types.contains("AGENT_TASK"));
        assertFalse(types.contains("EIP_TASK"), "EIP is not in automation either");
        assertTrue(types.contains("BUSINESS_RULE_TASK") && types.contains("START_EVENT"));
        assertTrue(api.nodeCatalog(acme).size() == tech.kayys.wayang.bpmn.ElementType.values().length || System.getenv("WAYANG_PROFILE") != null);
    }

    private static Map<String, Object> gatherProcess() {
        Map<String, Object> collect = Map.of("type", "collect", "key", "'all'", "size", 3, "timeoutSeconds", 60, "sweepTo", "late");
        return Map.of("id", "orders", "name", "Orders", "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "gather", "type", "EIP_TASK", "config", Map.of("message", "order", "pipeline", Map.of("steps", List.of(collect)))),
                Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "gather"), Map.of("id", "f2", "source", "gather", "target", "e")));
    }

    private void publishLate(Principal p) {
        api.saveDraft(p, DefinitionKind.PROCESS, "late", Map.of("id", "late", "name", "Late", "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "look", "type", "USER_TASK", "config", Map.of("title", "Look at the partial order batch")), Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "look"), Map.of("id", "f2", "source", "look", "target", "e"))), null);
        api.publish(p, DefinitionKind.PROCESS, "late");
    }

    @Test
    void aCollectThatSweepsToAProcessNeedsThatProcessPublished() {
        api.saveDraft(acme, DefinitionKind.PROCESS, "orders", gatherProcess(), null);
        var refused = assertThrows(ApiException.class, () -> api.publish(acme, DefinitionKind.PROCESS, "orders"));
        assertTrue(String.valueOf(refused.details()).contains("Process 'late' is not published"), String.valueOf(refused.details()));
        publishLate(acme);
        assertEquals(1, api.publish(acme, DefinitionKind.PROCESS, "orders").version());
        assertThrows(ApiException.class, () -> {
            api.saveDraft(globex, DefinitionKind.PROCESS, "orders", gatherProcess(), null);
            api.publish(globex, DefinitionKind.PROCESS, "orders"); // late is acme's, not globex's
        });
    }

    @Test
    void aCollectGroupPastItsDeadlineStartsTheLateProcessForItsOwnTenantOnly() {
        for (Principal p : List.of(acme, globex)) {
            publishLate(p);
            api.saveDraft(p, DefinitionKind.PROCESS, "orders", gatherProcess(), null);
            api.publish(p, DefinitionKind.PROCESS, "orders");
        }
        api.startRun(acme, "orders", null, Map.of("order", "o-1"));
        api.startRun(acme, "orders", null, Map.of("order", "o-2"));
        api.startRun(globex, "orders", null, Map.of("order", "g-1"));
        List<String> errors = new ArrayList<>();
        var soon = new CollectSweepService(store, api, aggregation, Clock.offset(Clock.systemUTC(), java.time.Duration.ofSeconds(30)), errors::add);
        assertEquals(0, soon.sweep(), "not due yet");
        var suspended = new CollectSweepService(store, api, aggregation, Clock.offset(Clock.systemUTC(), java.time.Duration.ofSeconds(90)), errors::add)
                .withTenantGate(TenantGate.suspended(java.util.Set.of("acme")));
        assertEquals(1, suspended.sweep(), "acme is suspended: its group waits, globex's is swept");
        assertTrue(api.listRuns(acme, "late", 10).isEmpty());
        assertEquals(1, api.listRuns(globex, "late", 10).size());
        var later = new CollectSweepService(store, api, aggregation, Clock.offset(Clock.systemUTC(), java.time.Duration.ofSeconds(90)), errors::add);
        assertEquals(1, later.sweep(), "acme is active again");
        List<RunView> late = api.listRuns(acme, "late", 10);
        assertEquals(1, late.size());
        assertEquals(RunStatus.WAITING, late.get(0).status());
        var agg = (Map<?, ?>) late.get(0).variables().get("aggregate");
        assertEquals(List.of("o-1", "o-2"), agg.get("bodies"));
        assertEquals(Boolean.TRUE, agg.get("incomplete"));
        assertEquals("collect-sweeper", late.get(0).startedBy());
        assertEquals(List.of("g-1"), ((Map<?, ?>) api.listRuns(globex, "late", 10).get(0).variables().get("aggregate")).get("bodies"));
        assertTrue(errors.isEmpty(), errors.toString());
        assertEquals(0, later.sweep());
    }
}
