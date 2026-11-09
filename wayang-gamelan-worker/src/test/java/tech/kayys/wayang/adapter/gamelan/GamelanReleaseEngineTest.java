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
import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessMaps;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.embed.ReleaseBundle;
import tech.kayys.wayang.embed.ReleaseDefinitions;
import tech.kayys.wayang.embed.ReleaseEngine;
import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.WaitTicket;
import tech.kayys.wayang.runtime.json.Json;

/** The release engine against an in-memory fake of the Gamelan client SDK (the real transport and server are not involved). */
class GamelanReleaseEngineTest {

    /** Fake Gamelan: stores runs and histories; tests flip their state by hand. */
    static final class Fake implements WorkflowRunClient, WorkflowDefinitionClient {
        final Map<String, RunResponse> runs = new LinkedHashMap<>();
        final Map<String, List<NodeExecutionRecord>> nodes = new LinkedHashMap<>();
        final Map<String, String> tenants = new LinkedHashMap<>();
        RuntimeException failDeploy;
        RuntimeException failCreate;
        final List<CreateRunRequest> requests = new ArrayList<>();
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
            if (failCreate != null) {
                return Uni.createFrom().failure(failCreate);
            }
            f.requests.add(r);
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
            if (failDeploy != null) {
                return Uni.createFrom().failure(failDeploy);
            }
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

    private final Fake gamelan = new Fake("release", null);
    private final WaitRegistry.InMemory registry = new WaitRegistry.InMemory();
    private WayangRuntime runtime;

    private static Map<String, Object> loan(String title) {
        return ProcessMaps.toMap(ProcessModel.builder("loan").name("Loan").start("s")
                .task("rule", ElementType.BUSINESS_RULE_TASK, Map.of("model", "risk"))
                .task("review", ElementType.USER_TASK, Map.of("title", title)).end("e")
                .flow("s", "rule").flow("rule", "review").flow("review", "e").build());
    }

    private static Map<String, Object> risk() {
        return Map.of("id", "risk", "decisions", List.of(Map.of("name", "level", "type", "table", "hitPolicy", "FIRST",
                "inputs", List.of("amount"), "outputs", List.of("level"), "rules", List.of(Map.of("when", List.of("-"), "then", List.of("\"low\""))))));
    }

    private static ReleaseBundle bundle(String title) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("format", "wayang.workspace");
        b.put("formatVersion", 1);
        b.put("project", Map.of("name", "Demo"));
        b.put("definitions", List.of(Map.of("kind", "PROCESS", "id", "loan", "publishedVersion", 3, "document", loan(title)),
                Map.of("kind", "DECISION", "id", "risk", "publishedVersion", 1, "document", risk())));
        b.put("requires", Map.of("secrets", List.of("API_KEY"), "externalSecrets", List.of(), "oauthConnections", List.of(), "config", List.of()));
        b.put("integrity", Map.of("sha256", "abc"));
        return ReleaseBundle.parse(Json.write(b));
    }

    private GamelanReleaseEngine engine(ReleaseBundle b) throws Exception {
        runtime = new WayangRuntime();
        ReleaseDefinitions defs = new ReleaseDefinitions(b);
        ReleaseComponents inner = new ReleaseComponents(defs, Map.of(), null, (t, n) -> n.equals("API_KEY") ? "k" : null, null);
        WayangComponents c = new WayangComponents() {
            @Override
            public tech.kayys.wayang.runtime.SecretResolver secretResolver() {
                return inner.secretResolver();
            }

            @Override
            public java.util.function.Function<String, tech.kayys.wayang.dmn.DecisionModel> decisionLookup() {
                return inner.decisionLookup();
            }

            @Override
            public WaitRegistry waitRegistry() {
                return registry;
            }
        };
        // A dynamic proxy rather than an anonymous class: the abstract methods of Instance differ between CDI versions (4.1 added several).
        @SuppressWarnings("unchecked")
        Instance<WayangComponents> inst = (Instance<WayangComponents>) java.lang.reflect.Proxy.newProxyInstance(
                Instance.class.getClassLoader(), new Class<?>[] {Instance.class}, (proxy, method, args) -> switch (method.getName()) {
                    case "isResolvable" -> true;
                    case "isUnsatisfied", "isAmbiguous" -> false;
                    case "get" -> c;
                    case "iterator" -> List.of(c).iterator();
                    case "toString" -> "test Instance";
                    case "hashCode" -> System.identityHashCode(proxy);
                    case "equals" -> proxy == args[0];
                    default -> throw new UnsupportedOperationException(method.getName());
                });
        var f = WayangRuntime.class.getDeclaredField("components");
        f.setAccessible(true);
        f.set(runtime, inst);
        return new GamelanReleaseEngine(b, defs, gamelan.client(), runtime, (t, n) -> n.equals("API_KEY") ? "k" : null,
                Clock.fixed(Instant.EPOCH, ZoneOffset.UTC));
    }

    private static NodeExecutionRecord node(String id, NodeExecutionStatus st, Map<String, Object> out) {
        return NodeExecutionRecord.builder().nodeId(id).status(st).outputs(out).attempt(1).build();
    }

    @Test
    void definitionsAreDeployedUnderTheReleaseTenantWithAContentHashedVersion() throws Exception {
        var e = engine(bundle("Approve"));
        assertFalse(e.ready(), "not ready before the definitions are on Gamelan");
        e.deploy();
        assertTrue(e.ready());
        assertEquals(1, gamelan.deployed.size());
        assertTrue(gamelan.deployed.get(0).startsWith("release~loan@3-"), gamelan.deployed.toString());
        assertEquals(gamelan.deployed.get(0), "release~loan@" + e.gamelanVersion("loan"));
        String v1 = e.gamelanVersion("loan");
        String v2 = engine(bundle("Approve now")).gamelanVersion("loan");
        assertNotEquals(v1, v2);
        assertEquals(v1, engine(bundle("Approve")).gamelanVersion("loan"), "the same content gives the same version");
        gamelan.failDeploy = new RuntimeException("409 workflow already exists");
        e.deploy(); // an older release already put this exact version there: fine
        gamelan.failDeploy = new RuntimeException("connection refused to http://secret-host:1/");
        var down = assertThrows(ReleaseEngine.Unavailable.class, e::deploy);
        assertFalse(down.getMessage().contains("secret-host"), "no endpoint details in the message: " + down.getMessage());
    }

    @Test
    void deployRetriesUntilGamelanIsUp() throws Exception {
        var e = engine(bundle("Approve"));
        gamelan.failDeploy = new RuntimeException("refused");
        var t = new Thread(() -> {
            try {
                Thread.sleep(300);
            } catch (InterruptedException ex) {
                return;
            }
            gamelan.failDeploy = null;
        });
        t.start();
        var live = new GamelanReleaseEngine(bundle("Approve"), new ReleaseDefinitions(bundle("Approve")), gamelan.client(), runtime, null, Clock.systemUTC());
        live.deployWithRetry(java.time.Duration.ofSeconds(10), java.time.Duration.ofMillis(100));
        assertEquals(1, gamelan.deployed.size());
        t.join();
        gamelan.failDeploy = new RuntimeException("refused");
        assertThrows(ReleaseEngine.Unavailable.class, () -> live.deployWithRetry(java.time.Duration.ofMillis(200), java.time.Duration.ofMillis(50)));
        assertNotNull(e);
    }

    @Test
    void aRunStartsWithTheTenantAndProcessSetByTheEngineNotTheCaller() throws Exception {
        var e = engine(bundle("Approve"));
        var r = e.start("loan", Map.of("amount", 5, "wayang.tenant", "evil"));
        assertEquals("loan", r.processId());
        assertEquals("RUNNING", r.status());
        assertEquals(1, gamelan.requests.size());
        var sent = gamelan.requests.get(0);
        assertEquals("release~loan", sent.getWorkflowId());
        assertEquals(e.gamelanVersion("loan"), sent.getWorkflowVersion());
        assertEquals("release", sent.getInputs().get(tech.kayys.wayang.runtime.SecretResolver.TENANT_VARIABLE));
        assertEquals("loan", sent.getInputs().get(tech.kayys.wayang.runtime.SecretResolver.PROCESS_VARIABLE));
        assertEquals(5, sent.getInputs().get("amount"));
        assertThrows(IllegalArgumentException.class, () -> e.start("nope", Map.of()));
        gamelan.failCreate = new RuntimeException("503 http://internal:9/ down");
        var down = assertThrows(ReleaseEngine.Unavailable.class, () -> e.start("loan", Map.of()));
        assertFalse(down.getMessage().contains("internal"));
    }

    @Test
    void aParkedWaitMakesTheRunWaitingAndCanBeCompletedHere() throws Exception {
        var e = engine(bundle("Approve"));
        var r = e.start("loan", Map.of());
        gamelan.nodes.put(r.id(), List.of(node("rule", NodeExecutionStatus.COMPLETED, Map.of("level", "high")),
                node("review", NodeExecutionStatus.EXECUTING, Map.of())));
        registry.register(r.id(), new WaitTicket("tok1", "review", "human", Map.of("title", "Approve"), null, null, null));
        var v = e.get(r.id()).orElseThrow();
        assertEquals("WAITING", v.status());
        assertEquals(List.of("rule"), v.executedNodes());
        assertEquals("high", v.variables().get("rule.level"));
        assertEquals("tok1", v.waiting().get(0).get("token"));
        assertEquals("Approve", v.waiting().get(0).get("title"));
        assertFalse(e.completeWait("nope", Map.of()), "an unknown token");
        assertEquals(1, e.list("loan", 10).size());
    }

    @Test
    void internalVariablesAreHiddenAndFailuresExplained() throws Exception {
        var e = engine(bundle("Approve"));
        var r = e.start("loan", Map.of());
        gamelan.runs.put(r.id(), new RunResponse(r.id(), "release~loan", "x", "FAILED", null, Instant.EPOCH, Instant.EPOCH, Instant.EPOCH, null, 0, 3, 1, 1,
                null, Map.of("wayang.tenant", "release", "amount", 5)));
        var v = e.get(r.id()).orElseThrow();
        assertEquals("FAILED", v.status());
        assertEquals(Map.of("amount", 5), v.variables());
        assertEquals("Run failed", v.error());
    }

    @Test
    void aRunOfAnotherWorkflowOrAnUnknownRunLooksMissing() throws Exception {
        var e = engine(bundle("Approve"));
        gamelan.runs.put("foreign", new RunResponse("foreign", "acme~loan", "1", "RUNNING", null, Instant.EPOCH, Instant.EPOCH, null, null, 0, 3, 1, 1, null,
                Map.of()));
        assertTrue(e.get("foreign").isEmpty());
        assertTrue(e.get("unknown").isEmpty(), "a 404 is a missing run, not an outage");
        assertFalse(e.cancel("foreign"));
        assertTrue(e.list(null, 10).isEmpty(), "other workflows are not listed");
    }

    @Test
    void cancelStopsARunningRunOnceAndNotAFinishedOne() throws Exception {
        var e = engine(bundle("Approve"));
        var r = e.start("loan", Map.of());
        registry.register(r.id(), new WaitTicket("tok2", "review", "human", Map.of(), null, null, null));
        assertTrue(e.cancel(r.id()));
        assertEquals(List.of(r.id()), gamelan.cancelled);
        assertFalse(e.cancel(r.id()), "already cancelled");
        assertEquals("CANCELLED", e.get(r.id()).orElseThrow().status());
    }

    @Test
    void missingSecretsAreReportedByNameOnly() throws Exception {
        assertEquals(List.of(), engine(bundle("Approve")).missingSecrets());
        var b = bundle("Approve");
        var none = new GamelanReleaseEngine(b, new ReleaseDefinitions(b), gamelan.client(), runtime, (t, n) -> null, Clock.systemUTC());
        assertEquals(List.of("API_KEY"), none.missingSecrets());
    }

    @Test
    void componentsResolveOnlyTheReleasesOwnQualifiedReferences() {
        var b = bundle("Approve");
        var c = new ReleaseComponents(new ReleaseDefinitions(b), Map.of(), null, null, null);
        assertNotNull(c.decisionLookup().apply("release/risk"));
        assertNull(c.decisionLookup().apply("acme/risk"), "another tenant's reference");
        assertNull(c.decisionLookup().apply("risk"), "an unqualified reference");
        assertNull(c.agentLookup().apply("release/none"));
        assertThrows(IllegalStateException.class, () -> c.agentInvoker().invoke(null, Map.of()));
    }

    @Test
    void aCollectGroupPastItsDeadlineStartsTheLateProcessOnGamelan() throws Exception {
        Map<String, Object> collect = Map.of("type", "collect", "key", "'all'", "size", 3, "timeoutSeconds", 60, "sweepTo", "loan");
        Map<String, Object> orders = Map.of("id", "orders", "name", "Orders", "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "gather", "type", "EIP_TASK", "config", Map.of("message", "order", "pipeline", Map.of("steps", List.of(collect)))),
                Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "gather"), Map.of("id", "f2", "source", "gather", "target", "e")));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("format", "wayang.workspace");
        b.put("formatVersion", 1);
        b.put("project", Map.of("name", "Demo"));
        b.put("definitions", List.of(Map.of("kind", "PROCESS", "id", "orders", "publishedVersion", 1, "document", orders),
                Map.of("kind", "PROCESS", "id", "loan", "publishedVersion", 3, "document", loan("Approve")),
                Map.of("kind", "DECISION", "id", "risk", "publishedVersion", 1, "document", risk())));
        b.put("requires", Map.of("secrets", List.of("API_KEY"), "externalSecrets", List.of(), "oauthConnections", List.of(), "config", List.of()));
        b.put("integrity", Map.of("sha256", "abc"));
        var e = engine(ReleaseBundle.parse(Json.write(b)));
        var store = new tech.kayys.wayang.eip.AggregationStore.InMemory(100);
        e.withCollectSweeping(store);
        e.deploy();
        store.append("release|orders|gather", "all", "{\"body\":\"o-1\"}", -120_000, 10);
        store.append("release|orders|gather", "all", "{\"body\":\"o-2\"}", -1_000, 10);
        store.append("release|other|gather", "all", "{\"body\":\"x\"}", -120_000, 10);
        assertEquals(1, e.fireDueTimers());
        assertEquals(1, gamelan.requests.size());
        var sent = gamelan.requests.get(0);
        assertEquals("release~loan", sent.getWorkflowId());
        var agg = (Map<?, ?>) sent.getInputs().get("aggregate");
        assertEquals(List.of("o-1", "o-2"), agg.get("bodies"));
        assertEquals(Boolean.TRUE, agg.get("incomplete"));
        assertEquals(0, e.fireDueTimers(), "not started twice; another process's namespace is not a sweep target");
        gamelan.failCreate = new RuntimeException("down");
        store.append("release|orders|gather", "all", "{\"body\":\"o-3\"}", -120_000, 10);
        assertEquals(0, e.fireDueTimers());
        gamelan.failCreate = null;
        assertEquals(1, e.fireDueTimers(), "a group Gamelan could not take is put back and started on the next tick");
    }
}
