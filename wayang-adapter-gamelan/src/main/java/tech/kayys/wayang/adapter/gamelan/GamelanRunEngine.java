package tech.kayys.wayang.adapter.gamelan;

import java.time.Clock;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.function.Function;

import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.node.NodeExecutionRecord;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.sdk.client.GamelanClient;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.EventBus;
import tech.kayys.wayang.api.RunEngine;
import tech.kayys.wayang.api.RunEvent;
import tech.kayys.wayang.api.RunStatus;
import tech.kayys.wayang.api.RunView;
import tech.kayys.wayang.api.TaskView;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.runtime.WaitRegistry;

/**
 * {@link RunEngine} on Gamelan: durable runs, with Wayang waits parked by the
 * executors and listed from the shared {@link WaitRegistry}.
 *
 * <ul>
 * <li>One Gamelan client per tenant (supplied by {@code clients}); tenant
 * ownership of a run is checked against the run's own tenant id on every call.</li>
 * <li>Definitions are deployed lazily per (tenant, process, version) under the
 * id {@code tenant~process}; references to decisions/agents are tenant-qualified
 * (see {@link TenantQualifier} and {@link StoreBackedComponents}).</li>
 * <li>Gamelan offers no event stream in its client SDK, so events are produced
 * by {@link #poll()} for runs started through this engine instance. Call it
 * every second or two. Runs started elsewhere, or before a restart, are not
 * announced.</li>
 * </ul>
 *
 * <p>
 * Not exercised against a live Gamelan: only the mapping logic is unit-tested.
 */
public final class GamelanRunEngine implements RunEngine {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final int MAX_TRACKED = 10_000;

    private final Function<String, GamelanClient> clients;
    private final WayangRuntime runtime;
    private final EventBus bus;
    private final Clock clock;
    private final Set<String> deployed = ConcurrentHashMap.newKeySet();

    private static final class Tracked {
        final String tenant;
        final Set<String> nodes = new HashSet<>();
        final Set<String> tokens = new HashSet<>();
        boolean started;

        Tracked(String tenant) {
            this.tenant = tenant;
        }
    }

    private final Map<String, Tracked> tracked = new ConcurrentHashMap<>();
    /** Run starter by run id: the SDK run object carries no labels, so remembered here (this instance only). */
    private final Map<String, String> starters = new ConcurrentHashMap<>();

    public GamelanRunEngine(Function<String, GamelanClient> clients, WayangRuntime runtime, EventBus bus, Clock clock) {
        this.clients = clients;
        this.runtime = runtime;
        this.bus = bus;
        this.clock = clock;
    }

    static String definitionId(String tenant, String processId) {
        return tenant + "~" + processId;
    }

    @Override
    public RunView start(String tenantId, String userId, String processId, int version, ExecutionPlan plan,
            Map<String, Object> callerInput) {
        Map<String, Object> input = new java.util.LinkedHashMap<>(callerInput == null ? Map.of() : callerInput);
        input.put(tech.kayys.wayang.runtime.SecretResolver.TENANT_VARIABLE, tenantId); // never caller-controlled
        input.put(tech.kayys.wayang.runtime.SecretResolver.PROCESS_VARIABLE, processId); // likewise
        GamelanClient c = clients.apply(tenantId);
        String defId = definitionId(tenantId, processId);
        String ver = String.valueOf(version);
        String key = defId + "@" + ver;
        if (!deployed.contains(key)) {
            ExecutionPlan qualified = TenantQualifier.apply(plan, tenantId);
            ExecutionPlan named = new ExecutionPlan(defId, qualified.processName(), qualified.nodes());
            try {
                c.workflows().create(GamelanPlanMapper.toDefinition(named, tenantId, ver)).await().atMost(CALL_TIMEOUT);
            } catch (RuntimeException e) {
                if (!looksLikeAlreadyExists(e)) {
                    throw unavailable("Cannot deploy process to Gamelan", e);
                }
            }
            deployed.add(key);
        }
        RunResponse r;
        try {
            r = c.runs().create(defId).version(ver).inputs(input).label("wayang.user", userId)
                    .label("wayang.process", processId).executeAndStart().await().atMost(CALL_TIMEOUT);
            starters.put(r.getRunId(), userId);
        } catch (RuntimeException e) {
            throw unavailable("Cannot start run on Gamelan", e);
        }
        if (tracked.size() < MAX_TRACKED) {
            tracked.put(r.getRunId(), new Tracked(tenantId));
        }
        publish(tenantId, r.getRunId(), RunEvent.Type.RUN_STARTED, null, Map.of("processId", processId, "version", version));
        Tracked t = tracked.get(r.getRunId());
        if (t != null) {
            t.started = true;
        }
        return view(new Snapshot(r, null), userId);
    }

    @Override
    public Optional<RunView> get(String tenantId, String runId) {
        return fetch(tenantId, runId).map(x -> view(x, starters.get(runId)));
    }

    @Override
    public List<RunView> list(String tenantId, String processId, int limit) {
        try {
            var q = clients.apply(tenantId).runs().query().size(limit);
            if (processId != null) {
                q.workflowId(definitionId(tenantId, processId));
            }
            List<RunView> out = new ArrayList<>();
            for (RunResponse r : q.execute().await().atMost(CALL_TIMEOUT)) {
                if (r.getWorkflowId() != null && r.getWorkflowId().startsWith(tenantId + "~")) {
                    out.add(view(new Snapshot(r, null), starters.get(r.getRunId())));
                }
            }
            return out;
        } catch (RuntimeException e) {
            throw unavailable("Cannot list runs on Gamelan", e);
        }
    }

    @Override
    public boolean completeWait(String tenantId, String token, Map<String, Object> outputs) {
        var reg = runtime.registry().find(token);
        if (reg.isEmpty() || fetch(tenantId, reg.get().runId()).isEmpty()) {
            return false;
        }
        boolean ok = runtime.completeWait(token, outputs);
        if (ok) {
            publish(tenantId, reg.get().runId(), RunEvent.Type.TASK_COMPLETED, reg.get().ticket().nodeId(),
                    Map.of("token", token));
        }
        return ok;
    }

    @Override
    public boolean cancel(String tenantId, String runId) {
        Optional<Snapshot> r = fetch(tenantId, runId);
        if (r.isEmpty() || mapStatus(r.get().run().getStatus(), false).isTerminal()) {
            return false;
        }
        for (WaitRegistry.Registered w : runtime.registry().listByRun(runId)) {
            runtime.cancelWait(w.ticket().token(), "Run cancelled");
        }
        try {
            clients.apply(tenantId).runs().cancel(runId, "Cancelled via Wayang API").await().atMost(CALL_TIMEOUT);
        } catch (RuntimeException e) {
            throw unavailable("Cannot cancel run on Gamelan", e);
        }
        publish(tenantId, runId, RunEvent.Type.RUN_CANCELLED, null, Map.of());
        tracked.remove(runId);
        return true;
    }

    @Override
    public int fireDueTimers() {
        return runtime.fireDueTimers();
    }

    /** Announces progress of runs started here; drops runs that finished. Call periodically. */
    @Override
    public void poll() {
        for (Map.Entry<String, Tracked> e : tracked.entrySet()) {
            Tracked t = e.getValue();
            if (!t.started) {
                continue;
            }
            Optional<Snapshot> r;
            try {
                r = fetch(t.tenant, e.getKey());
            } catch (RuntimeException ex) {
                continue; // Gamelan briefly unreachable: try again next tick
            }
            if (r.isEmpty()) {
                tracked.remove(e.getKey());
                continue;
            }
            RunView v = view(r.get(), null);
            for (NodeExecutionRecord n : nodes(r.get())) {
                if (isDone(n.getStatus()) && t.nodes.add(n.getNodeId())) {
                    publish(t.tenant, v.id(), RunEvent.Type.NODE_COMPLETED, n.getNodeId(), Map.of());
                }
            }
            for (TaskView task : v.waiting()) {
                if (t.tokens.add(task.token())) {
                    publish(t.tenant, v.id(), RunEvent.Type.TASK_CREATED, task.nodeId(),
                            Map.of("token", task.token(), "kind", task.kind()));
                }
            }
            if (v.status() == RunStatus.COMPLETED) {
                publish(t.tenant, v.id(), RunEvent.Type.RUN_COMPLETED, null, Map.of());
                tracked.remove(e.getKey());
            } else if (v.status() == RunStatus.FAILED) {
                publish(t.tenant, v.id(), RunEvent.Type.RUN_FAILED, null, Map.of("error", String.valueOf(v.error())));
                tracked.remove(e.getKey());
            } else if (v.status() == RunStatus.CANCELLED) {
                tracked.remove(e.getKey());
            }
        }
    }

    // ------------------------------------------------------------------ mapping

    private record Snapshot(RunResponse run, ExecutionHistory history) {
    }

    /** Run plus its history; empty when the run is missing or belongs to another tenant. */
    private Optional<Snapshot> fetch(String tenantId, String runId) {
        try {
            GamelanClient c = clients.apply(tenantId);
            RunResponse r = c.runs().get(runId).await().atMost(CALL_TIMEOUT);
            if (r == null || r.getWorkflowId() == null || !r.getWorkflowId().startsWith(tenantId + "~")) {
                return Optional.empty(); // another tenant's run looks exactly like a missing one
            }
            ExecutionHistory h = c.runs().getHistory(runId).await().atMost(CALL_TIMEOUT);
            if (h != null && h.getTenantId() != null && !tenantId.equals(h.getTenantId())) {
                return Optional.empty();
            }
            return Optional.of(new Snapshot(r, h));
        } catch (RuntimeException e) {
            if (looksLikeNotFound(e)) {
                return Optional.empty();
            }
            throw unavailable("Cannot read run from Gamelan", e);
        }
    }

    RunView view(Snapshot snap, String startedBy) {
        RunResponse r = snap.run();
        List<TaskView> waiting = new ArrayList<>();
        for (WaitRegistry.Registered w : runtime.registry().listByRun(r.getRunId())) {
            waiting.add(task(r.getRunId(), w.ticket()));
        }
        RunStatus status = mapStatus(r.getStatus(), !waiting.isEmpty());
        if (status.isTerminal()) {
            waiting.clear(); // stale tickets of a finished run are not actionable
        }
        String def = r.getWorkflowId();
        String processId = def.substring(def.indexOf('~') + 1);
        int version = 0;
        try {
            version = Integer.parseInt(r.getWorkflowVersion());
        } catch (RuntimeException ignored) {
            // leave 0
        }
        Map<String, Object> vars = new java.util.LinkedHashMap<>();
        if (r.getOutputs() != null) {
            vars.putAll(r.getOutputs());
        }
        List<String> executed = new ArrayList<>();
        String error = status == RunStatus.FAILED ? r.getErrorMessage() : null;
        for (NodeExecutionRecord n : nodes(snap)) {
            if (isDone(n.getStatus())) {
                executed.add(n.getNodeId());
                if (n.getOutputs() != null) {
                    n.getOutputs().forEach((k, v) -> vars.putIfAbsent(n.getNodeId() + "." + k, v));
                }
            }
            if (status == RunStatus.FAILED && error == null && n.getStatus() == NodeExecutionStatus.FAILED && n.getError() != null) {
                error = n.getNodeId() + ": " + n.getError();
            }
        }
        if (status == RunStatus.FAILED && error == null) {
            error = "Run failed";
        }
        return new RunView(r.getRunId(), snap.history() != null && snap.history().getTenantId() != null
                ? snap.history().getTenantId() : def.substring(0, def.indexOf('~')), processId, version, status, vars, executed, waiting,
                error, startedBy, r.getStartedAt() != null ? r.getStartedAt() : r.getCreatedAt(), r.getCompletedAt());
    }

    static RunStatus mapStatus(String gamelan, boolean parked) {
        if (gamelan == null) {
            return RunStatus.RUNNING;
        }
        return switch (gamelan) {
            case "COMPLETED" -> RunStatus.COMPLETED;
            case "FAILED", "COMPENSATED" -> RunStatus.FAILED;
            case "CANCELLED" -> RunStatus.CANCELLED;
            case "SUSPENDED" -> RunStatus.WAITING;
            default -> parked ? RunStatus.WAITING : RunStatus.RUNNING;
        };
    }

    private static boolean isDone(NodeExecutionStatus s) {
        return s == NodeExecutionStatus.COMPLETED || s == NodeExecutionStatus.SUCCESS || s == NodeExecutionStatus.FAILED
                || s == NodeExecutionStatus.CANCELLED || s == NodeExecutionStatus.SKIPPED;
    }

    private static List<NodeExecutionRecord> nodes(Snapshot s) {
        return s.history() == null || s.history().getNodeExecutions() == null ? List.of() : s.history().getNodeExecutions();
    }

    private static TaskView task(String runId, tech.kayys.wayang.runtime.WaitTicket t) {
        Object title = t.info().get("title");
        Object assignee = t.info().get("assignee");
        return new TaskView(t.token(), runId, t.nodeId(), t.kind(), title == null ? null : String.valueOf(title),
                assignee == null ? null : String.valueOf(assignee), t.info(), t.dueAt(), t.messageName(), t.correlationKey());
    }

    private void publish(String tenant, String runId, RunEvent.Type type, String nodeId, Map<String, Object> data) {
        bus.publish(new RunEvent(type, tenant, runId, nodeId, clock.instant(), data));
    }

    private static boolean looksLikeAlreadyExists(Throwable e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("already exist") || m.contains("duplicate") || m.contains("409") || m.contains("conflict");
    }

    private static boolean looksLikeNotFound(Throwable e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(java.util.Locale.ROOT);
        return m.contains("not found") || m.contains("404");
    }

    private static ApiException unavailable(String what, Throwable e) {
        return new ApiException(ApiException.Code.UNAVAILABLE, what + ": " + e.getMessage());
    }
}
