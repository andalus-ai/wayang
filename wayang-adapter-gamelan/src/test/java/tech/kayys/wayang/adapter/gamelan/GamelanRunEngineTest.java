package tech.kayys.wayang.adapter.gamelan;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.inject.Instance;
import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.node.NodeExecutionRecord;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.run.CreateRunRequest;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.engine.workflow.WorkflowDefinition;
import tech.kayys.gamelan.sdk.client.GamelanClient;
import tech.kayys.gamelan.sdk.client.GamelanClientConfig;
import tech.kayys.gamelan.sdk.client.WorkflowDefinitionClient;
import tech.kayys.gamelan.sdk.client.WorkflowDefinitionOperations;
import tech.kayys.gamelan.sdk.client.WorkflowRunClient;
import tech.kayys.gamelan.sdk.client.WorkflowRunOperations;
import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.run.RunEvent;
import tech.kayys.wayang.api.run.RunStatus;
import tech.kayys.wayang.api.run.RunView;
import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.WaitTicket;

/** Exercises the engine's mapping and tenancy logic against an in-memory fake of the Gamelan client SDK. */
class GamelanRunEngineTest {

    /** Fake Gamelan: stores runs and histories; tests flip their state by hand. */
    static final class Fake implements WorkflowRunClient, WorkflowDefinitionClient {
        final Map<String, RunResponse> runs = new LinkedHashMap<>();
        final Map<String, List<NodeExecutionRecord>> nodes = new LinkedHashMap<>();
        final Map<String, String> tenants = new LinkedHashMap<>();
        final List<String> deployed = new ArrayList<>();
        final List<String> cancelled = new ArrayList<>();
        final String tenant;
        final Fake shared;
        int seq;

        Fake(String tenant, Fake shared) {
            this.tenant = tenant;
            this.shared = shared;
        }

        Fake root() {
            return shared == null ? this : shared;
        }

        @Override
        public Uni<RunResponse> createRun(CreateRunRequest r) {
            Fake f = root();
            String id = "run-" + (++f.seq);
            RunResponse resp = new RunResponse(id, r.getWorkflowId(), r.getWorkflowVersion(), "RUNNING", null, Instant.EPOCH,
                    Instant.EPOCH, null, null, 0, 3, 1, 1, null, Map.of());
            f.runs.put(id, resp);
            f.tenants.put(id, tenant);
            return Uni.createFrom().item(resp);
        }

        @Override
        public Uni<RunResponse> getRun(String id) {
            RunResponse r = root().runs.get(id);
            return r == null ? Uni.createFrom().failure(new RuntimeException("404 not found")) : Uni.createFrom().item(r);
        }

        @Override
        public Uni<RunResponse> startRun(String id) {
            return getRun(id);
        }

        @Override
        public Uni<RunResponse> suspendRun(String a, String b, String c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<RunResponse> resumeRun(String a, Map<String, Object> b, String c) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<Void> cancelRun(String id, String reason) {
            Fake f = root();
            f.cancelled.add(id);
            RunResponse r = f.runs.get(id);
            f.runs.put(id, new RunResponse(id, r.getWorkflowId(), r.getWorkflowVersion(), "CANCELLED", null, r.getCreatedAt(),
                    r.getStartedAt(), Instant.EPOCH, null, 0, 3, 1, 1, null, Map.of()));
            return Uni.createFrom().voidItem();
        }

        @Override
        public Uni<Void> signal(String a, String b, String c, Map<String, Object> d) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<ExecutionHistory> getExecutionHistory(String id) {
            Fake f = root();
            return Uni.createFrom().item(new ExecutionHistory(null, null, "1", f.tenants.get(id), null, null, null,
                    f.nodes.getOrDefault(id, List.of()), null, null, null, null, null));
        }

        @Override
        public Uni<List<RunResponse>> queryRuns(String wf, String status, int page, int size) {
            List<RunResponse> out = new ArrayList<>();
            for (RunResponse r : root().runs.values()) {
                if (wf == null || wf.equals(r.getWorkflowId())) {
                    out.add(r);
                }
            }
            return Uni.createFrom().item(out);
        }

        @Override
        public Uni<Long> getActiveRunsCount() {
            return Uni.createFrom().item(0L);
        }

        @Override
        public Uni<WorkflowDefinition> createWorkflow(WorkflowDefinition d) {
            root().deployed.add(d.id().value() + "@" + d.version());
            return Uni.createFrom().item(d);
        }

        @Override
        public Uni<WorkflowDefinition> getWorkflow(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<WorkflowDefinition> getWorkflowByName(String n) {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<List<WorkflowDefinition>> listWorkflows() {
            throw new UnsupportedOperationException();
        }

        @Override
        public Uni<Void> deleteWorkflow(String id) {
            throw new UnsupportedOperationException();
        }

        @Override
        public void close() {
        }

        GamelanClient client() {
            Fake self = this;
            return new GamelanClient() {
                @Override
                public GamelanClientConfig config() {
                    return null;
                }

                @Override
                public WorkflowRunOperations runs() {
                    return new WorkflowRunOperations(self);
                }

                @Override
                public WorkflowDefinitionOperations workflows() {
                    return new WorkflowDefinitionOperations(self);
                }

                @Override
                public void close() {
                }
            };
        }
    }

    private final Fake root = new Fake("acme", null);
    private final Fake globexSide = new Fake("globex", root);
    private final WaitRegistry.InMemory registry = new WaitRegistry.InMemory();
    private final EventBus bus = new EventBus();
    private final List<RunEvent> events = new ArrayList<>();
    private GamelanRunEngine engine;
    private WayangRuntime runtime;

    private GamelanRunEngine engine() throws Exception {
        runtime = new WayangRuntime();
        WayangComponents c = new WayangComponents() {
            @Override
            public WaitRegistry waitRegistry() {
                return registry;
            }
        };
        Instance<WayangComponents> inst = new Instance<>() {
            @Override
            public boolean isResolvable() {
                return true;
            }

            @Override
            public WayangComponents get() {
                return c;
            }

            @Override
            public java.util.Iterator<WayangComponents> iterator() {
                return List.of(c).iterator();
            }

            @Override
            public Iterable<? extends Handle<WayangComponents>> handles() {
                return List.of(); // CDI 4.1 made this abstract; nothing here uses handles
            }
        };
        var f = WayangRuntime.class.getDeclaredField("components");
        f.setAccessible(true);
        f.set(runtime, inst);
        bus.subscribe("acme", null, events::add);
        return new GamelanRunEngine(t -> t.equals("acme") ? root.client() : globexSide.client(), runtime, bus,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static tech.kayys.wayang.bpmn.ExecutionPlan plan() {
        return ProcessCompiler.compile(ProcessModel.builder("loan").start("s")
                .task("rule", ElementType.BUSINESS_RULE_TASK, Map.of("model", "risk"))
                .task("review", ElementType.USER_TASK, Map.of("title", "Approve")).end("e")
                .flow("s", "rule").flow("rule", "review").flow("review", "e").build());
    }

    private static NodeExecutionRecord node(String id, NodeExecutionStatus st, Map<String, Object> out) {
        return NodeExecutionRecord.builder().nodeId(id).status(st).outputs(out).attempt(1).build();
    }

    @Test
    void startDeploysOnceUnderTenantQualifiedIds() throws Exception {
        engine = engine();
        RunView r1 = engine.start("acme", "ann", "loan", 3, plan(), Map.of("amount", 5));
        engine.start("acme", "ann", "loan", 3, plan(), Map.of());
        assertEquals(List.of("acme~loan@3"), root.deployed);
        assertEquals("loan", r1.processId());
        assertEquals(3, r1.version());
        assertEquals("acme", r1.tenantId());
        assertEquals(RunStatus.RUNNING, r1.status());
        engine.start("acme", "ann", "loan", 4, plan(), Map.of());
        assertEquals(2, root.deployed.size());
    }

    @Test
    void parkedWaitMakesRunWaitingAndCompletes() throws Exception {
        engine = engine();
        RunView r = engine.start("acme", "ann", "loan", 1, plan(), Map.of());
        root.nodes.put(r.id(), List.of(node("rule", NodeExecutionStatus.COMPLETED, Map.of("level", "high")),
                node("review", NodeExecutionStatus.EXECUTING, Map.of())));
        registry.register(r.id(), new WaitTicket("tok1", "review", "human", Map.of("title", "Approve"), null, null, null));
        List<Object> done = new ArrayList<>();
        runtime.waits().park("tok1", new PendingWaits.Pending(null, Map.of(), new io.smallrye.mutiny.subscription.UniEmitter<Object>() {
            public void complete(Object o) {
                done.add(o);
            }

            public void fail(Throwable t) {
                done.add(t);
            }
        }, out -> null));
        RunView v = engine.get("acme", r.id()).orElseThrow();
        assertEquals(RunStatus.WAITING, v.status());
        assertEquals("Approve", v.waiting().get(0).title());
        assertEquals(List.of("rule"), v.executedNodes());
        assertEquals("high", v.variables().get("rule.level"));
        assertFalse(engine.completeWait("globex", "tok1", Map.of()), "other tenant must not complete it");
        assertTrue(done.isEmpty());
        assertTrue(engine.completeWait("acme", "tok1", Map.of("approved", true)));
        assertEquals(1, done.size());
        assertFalse(engine.completeWait("acme", "tok1", Map.of()), "second completion is refused");
    }

    @Test
    void otherTenantsRunLooksMissing() throws Exception {
        engine = engine();
        RunView r = engine.start("acme", "ann", "loan", 1, plan(), Map.of());
        assertTrue(engine.get("globex", r.id()).isEmpty());
        assertFalse(engine.cancel("globex", r.id()));
        assertTrue(engine.list("globex", null, 10).isEmpty());
        assertEquals(1, engine.list("acme", null, 10).size());
        assertEquals(1, engine.list("acme", "loan", 10).size());
        assertTrue(root.cancelled.isEmpty());
    }

    @Test
    void cancelFailsParkedWaitsAndCancelsRun() throws Exception {
        engine = engine();
        RunView r = engine.start("acme", "ann", "loan", 1, plan(), Map.of());
        registry.register(r.id(), new WaitTicket("tok2", "review", "human", Map.of(), null, null, null));
        List<Object> done = new ArrayList<>();
        runtime.waits().park("tok2", new PendingWaits.Pending(null, Map.of(), new io.smallrye.mutiny.subscription.UniEmitter<Object>() {
            public void complete(Object o) {
                done.add(o);
            }

            public void fail(Throwable t) {
                done.add(t);
            }
        }, out -> null));
        assertTrue(engine.cancel("acme", r.id()));
        assertEquals(List.of(r.id()), root.cancelled);
        assertTrue(done.get(0) instanceof Throwable);
        assertEquals(RunStatus.CANCELLED, engine.get("acme", r.id()).orElseThrow().status());
        assertFalse(engine.cancel("acme", r.id()), "already terminal");
        assertTrue(events.stream().anyMatch(e -> e.type() == RunEvent.Type.RUN_CANCELLED));
    }

    @Test
    void pollAnnouncesProgressOnce() throws Exception {
        engine = engine();
        RunView r = engine.start("acme", "ann", "loan", 1, plan(), Map.of());
        root.nodes.put(r.id(), List.of(node("rule", NodeExecutionStatus.COMPLETED, Map.of())));
        engine.poll();
        engine.poll();
        assertEquals(1, events.stream().filter(e -> e.type() == RunEvent.Type.NODE_COMPLETED).count());
        root.nodes.put(r.id(), List.of(node("rule", NodeExecutionStatus.COMPLETED, Map.of()),
                node("review", NodeExecutionStatus.COMPLETED, Map.of())));
        root.runs.put(r.id(), new RunResponse(r.id(), "acme~loan", "1", "COMPLETED", null, Instant.EPOCH, Instant.EPOCH,
                Instant.EPOCH, null, 2, 3, 1, 1, null, Map.of()));
        engine.poll();
        engine.poll();
        assertEquals(2, events.stream().filter(e -> e.type() == RunEvent.Type.NODE_COMPLETED).count());
        assertEquals(1, events.stream().filter(e -> e.type() == RunEvent.Type.RUN_COMPLETED).count());
    }

    @Test
    void failedRunReportsNodeError() throws Exception {
        engine = engine();
        RunView r = engine.start("acme", "ann", "loan", 1, plan(), Map.of());
        root.runs.put(r.id(), new RunResponse(r.id(), "acme~loan", "1", "FAILED", null, Instant.EPOCH, Instant.EPOCH,
                Instant.EPOCH, null, 1, 3, 1, 1, "boom", Map.of()));
        RunView v = engine.get("acme", r.id()).orElseThrow();
        assertEquals(RunStatus.FAILED, v.status());
        assertEquals("boom", v.error());
    }

    @Test
    void qualifierRewritesReferencesAndStoreResolvesThem() {
        var q = TenantQualifier.apply(plan(), "acme");
        assertEquals("acme/risk", q.node("rule").orElseThrow().config().get("model"));
        assertEquals("Approve", q.node("review").orElseThrow().config().get("title"));
        assertArrayEquals(new String[] { "acme", "risk" }, TenantQualifier.split("acme/risk"));
        assertNull(TenantQualifier.split("risk"));

        var store = new DefinitionStore.InMemory();
        var doc = Map.<String, Object>of("id", "risk", "decisions", List.of(Map.of("name", "level", "type", "table",
                "hitPolicy", "FIRST", "inputs", List.of("amount"), "outputs", List.of("level"),
                "rules", List.of(Map.of("when", List.of("-"), "then", List.of("\"low\""))))));
        store.putDraft("acme", DefinitionKind.DECISION, "risk", doc, null, "u", Instant.EPOCH);
        var comps = new StoreBackedComponents(store);
        assertNull(comps.decisionLookup().apply("acme/risk"), "unpublished decisions are invisible");
        store.publish("acme", DefinitionKind.DECISION, "risk", "u", Instant.EPOCH);
        assertNotNull(comps.decisionLookup().apply("acme/risk"));
        assertNull(comps.decisionLookup().apply("globex/risk"), "other tenants cannot resolve it");
        assertNull(comps.decisionLookup().apply("risk"));
    }
}
