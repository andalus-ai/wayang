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

class ProjectAuthTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);
    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final EventBus bus = new EventBus();
    private final RunEngine engine = new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, clock);
    private final WayangApi api = new WayangApi(store, engine, waits, bus, clock);

    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final String a = api.createProject(admin, "A", null).id();
    private final String b = api.createProject(admin, "B", null).id();

    private static final Set<Permission> ALL = Set.of(Permission.DEFINITION_READ, Permission.DEFINITION_WRITE, Permission.RUN_READ,
            Permission.RUN_START, Permission.RUN_CANCEL, Permission.TASK_COMPLETE, Permission.MESSAGE_SEND);

    private Principal projectOnly(String project) {
        return new Principal("acme", "alice", Set.of(), Set.of(), Map.of(project, ALL));
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> r = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            r.put((String) kv[i], kv[i + 1]);
        }
        return r;
    }

    private Map<String, Object> proc(String id, String project) {
        Map<String, Object> d = m("id", id, "name", id, "elements", List.of(m("id", "s", "type", "START_EVENT"),
                m("id", "u", "type", "USER_TASK", "config", m("title", "Do " + id)), m("id", "e", "type", "END_EVENT")),
                "flows", List.of(m("id", "f1", "source", "s", "target", "u"), m("id", "f2", "source", "u", "target", "e")));
        if (project != null) {
            d.put("studio", m("projectId", project));
        }
        return d;
    }

    private void publish(String id, String project) {
        api.saveDraft(admin, DefinitionKind.PROCESS, id, proc(id, project), null);
        api.publish(admin, DefinitionKind.PROCESS, id);
    }

    private static ApiException.Code code(Runnable r) {
        return assertThrows(ApiException.class, r::run).code();
    }

    @Test
    void definitionsOutsideTheCallersProjectsAreInvisible() {
        publish("pa", a);
        publish("pb", b);
        publish("loose", null);
        Principal alice = projectOnly(a);
        assertEquals(List.of("pa"), api.list(alice, DefinitionKind.PROCESS).stream().map(DefinitionSummary::id).toList());
        assertEquals(3, api.list(admin, DefinitionKind.PROCESS).size());
        assertDoesNotThrow(() -> api.getVersion(alice, DefinitionKind.PROCESS, "pa", null));
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.getVersion(alice, DefinitionKind.PROCESS, "pb", null)));
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.getVersion(alice, DefinitionKind.PROCESS, "loose", 1)));
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.getVersion(alice, DefinitionKind.PROCESS, "nope", null)),
                "hidden and missing look the same");
        api.saveDraft(admin, DefinitionKind.PROCESS, "pb", proc("pb", b), api.getDraft(admin, DefinitionKind.PROCESS, "pb").revision());
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.getDraft(alice, DefinitionKind.PROCESS, "pb")));
        assertThrows(ApiException.class, () -> api.exportBpmn(alice, "pb", null));
    }

    @Test
    void writesAreConfinedToTheCallersProjectAndCannotMoveDefinitionsAcrossTheBoundary() {
        Principal alice = projectOnly(a);
        assertDoesNotThrow(() -> api.saveDraft(alice, DefinitionKind.PROCESS, "mine", proc("mine", a), null));
        assertEquals(ApiException.Code.FORBIDDEN, code(() -> api.saveDraft(alice, DefinitionKind.PROCESS, "x", proc("x", b), null)));
        assertEquals(ApiException.Code.FORBIDDEN, code(() -> api.saveDraft(alice, DefinitionKind.PROCESS, "y", proc("y", null), null)),
                "unassigned definitions need tenant-wide write");
        // moving mine -> B needs write on B
        long rev = api.getDraft(alice, DefinitionKind.PROCESS, "mine").revision();
        assertEquals(ApiException.Code.FORBIDDEN, code(() -> api.saveDraft(alice, DefinitionKind.PROCESS, "mine", proc("mine", b), rev)));
        // taking over B's definition by writing its id into A needs write on B (the source)
        publish("pb", b);
        assertEquals(ApiException.Code.FORBIDDEN, code(() -> api.saveDraft(alice, DefinitionKind.PROCESS, "pb", proc("pb", a), null)));
        // publish / delete of other projects' drafts: not found
        api.saveDraft(admin, DefinitionKind.PROCESS, "pb", proc("pb", b), api.getDraft(admin, DefinitionKind.PROCESS, "pb").revision());
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.publish(alice, DefinitionKind.PROCESS, "pb")));
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.deleteDraft(alice, DefinitionKind.PROCESS, "pb")));
        assertTrue(api.deleteDraft(alice, DefinitionKind.PROCESS, "mine"));
    }

    @Test
    void projectsThemselves() {
        Principal alice = projectOnly(a);
        assertEquals(List.of(a), api.listProjects(alice).stream().map(Project::id).toList());
        assertEquals(2, api.listProjects(admin).size());
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.getProject(alice, b)));
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.updateProject(alice, b, "x", null)));
        assertDoesNotThrow(() -> api.updateProject(alice, a, "A2", null));
        assertEquals(ApiException.Code.FORBIDDEN, code(() -> api.createProject(alice, "C", null)));
        assertEquals(ApiException.Code.FORBIDDEN, code(() -> api.deleteProject(alice, a)));
    }

    @Test
    void runsTasksAndEventsAreConfinedToTheCallersProjects() {
        publish("pa", a);
        publish("pb", b);
        Principal alice = projectOnly(a);
        List<RunEvent> seen = new ArrayList<>();
        assertDoesNotThrow(() -> api.subscribe(alice, null, seen::add));
        RunView mine = api.startRun(alice, "pa", null, Map.of());
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.startRun(alice, "pb", null, Map.of())));
        RunView theirs = api.startRun(admin, "pb", null, Map.of());
        RunView mine2 = api.startRun(admin, "pa", null, Map.of());

        assertEquals(mine.id(), api.getRun(alice, mine.id()).id());
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.getRun(alice, theirs.id())));
        assertEquals(Set.of(mine.id(), mine2.id()), api.listRuns(alice, null, 50).stream().map(RunView::id).collect(java.util.stream.Collectors.toSet()));
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.cancelRun(alice, theirs.id())));
        assertEquals(RunStatus.WAITING, api.getRun(admin, theirs.id()).status(), "the foreign run was not touched");

        List<TaskView> tasks = api.listTasks(alice, 50);
        assertEquals(2, tasks.size());
        assertTrue(tasks.stream().allMatch(t -> t.title().equals("Do pa")));
        String foreign = api.listTasks(admin, 50).stream().filter(t -> t.runId().equals(theirs.id())).findFirst().orElseThrow().token();
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.completeTask(alice, foreign, Map.of())));
        assertDoesNotThrow(() -> api.completeTask(alice, tasks.get(0).token(), Map.of()));

        assertFalse(seen.isEmpty());
        assertTrue(seen.stream().allMatch(e -> !e.runId().equals(theirs.id())), "no events from other projects");
        assertTrue(seen.stream().anyMatch(e -> e.runId().equals(mine2.id())));
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.subscribe(alice, theirs.id(), seen::add)));
    }

    @Test
    void messagesCannotBeBufferedOrDeliveredAcrossProjects() {
        Principal alice = projectOnly(a);
        assertEquals(ApiException.Code.FORBIDDEN, code(() -> api.sendMessage(alice, "ping", "k1", Map.of())),
                "buffering is tenant-wide");
        assertEquals("BUFFERED", api.sendMessage(admin, "ping", "k1", Map.of()));
    }

    @Test
    void notYetProjectAwareFeaturesStayClosedToProjectScopedCallers() {
        Principal alice = projectOnly(a);
        for (Permission perm : Permission.values()) {
            assertFalse(alice.can(perm), perm.name() + " must not count tenant-wide");
        }
        assertEquals(ApiException.Code.FORBIDDEN, code(() -> api.fireTimers(alice)));
        assertEquals(ApiException.Code.FORBIDDEN, code(() -> new SecretService(new InMemorySecretStore(), new SecretCipher(Map.of("k", new byte[32]), "k"), clock).list(alice)));
    }

    @Test
    void aTenantWidePermissionStillCoversEveryProject() {
        publish("pb", b);
        Principal reader = new Principal("acme", "rita", Set.of(Role.VIEWER));
        assertEquals(1, api.list(reader, DefinitionKind.PROCESS).size());
        assertDoesNotThrow(() -> api.getVersion(reader, DefinitionKind.PROCESS, "pb", null));
        assertEquals(2, api.listProjects(reader).size());
    }

    @Test
    void aProjectGrantInAnotherTenantMeansNothing() {
        publish("pa", a);
        Principal mallory = new Principal("globex", "mal", Set.of(), Set.of(), Map.of(a, ALL));
        assertTrue(api.list(mallory, DefinitionKind.PROCESS).isEmpty());
        assertEquals(ApiException.Code.NOT_FOUND, code(() -> api.startRun(mallory, "pa", null, Map.of())));
    }

    @Test
    void theProcessMcpServerOnlyOffersTheCallersProjects() {
        Map<String, Object> pa = proc("pa", a);
        ((Map<String, Object>) pa.get("studio")).put("mcp", m("expose", true));
        Map<String, Object> pb = proc("pb", b);
        ((Map<String, Object>) pb.get("studio")).put("mcp", m("expose", true));
        api.saveDraft(admin, DefinitionKind.PROCESS, "pa", pa, null);
        api.publish(admin, DefinitionKind.PROCESS, "pa");
        api.saveDraft(admin, DefinitionKind.PROCESS, "pb", pb, null);
        api.publish(admin, DefinitionKind.PROCESS, "pb");
        var svc = new ProcessMcpService(api, store, 10);
        assertEquals(List.of("run_pa"), svc.list(projectOnly(a)).stream().map(x -> (String) x.get("tool")).toList());
        assertEquals(2, svc.list(admin).size());
    }
}
