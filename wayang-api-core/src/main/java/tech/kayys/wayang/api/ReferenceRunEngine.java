package tech.kayys.wayang.api;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tech.kayys.wayang.agent.AgentInvoker;
import tech.kayys.wayang.agent.AgentSpec;
import tech.kayys.wayang.agent.AgentSpecLoader;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.dmn.DmnLoader;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.runtime.AgentHandler;
import tech.kayys.wayang.runtime.ControlHandler;
import tech.kayys.wayang.runtime.DmnHandler;
import tech.kayys.wayang.runtime.EipHandler;
import tech.kayys.wayang.runtime.HumanHandler;
import tech.kayys.wayang.runtime.NodeRunner;
import tech.kayys.wayang.runtime.ReferenceEngine;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.WaitTicket;

/**
 * In-memory {@link RunEngine} on top of the single-threaded
 * {@link ReferenceEngine}. For development, demos and tests: runs are lost on
 * restart and each run is processed under a lock. Decisions and agents are
 * resolved from the latest <em>published</em> definitions of the tenant at run
 * start.
 */
public final class ReferenceRunEngine implements RunEngine {

    private static final int MAX_RETAINED_RUNS = 10_000;

    private static final class State {
        final String tenant;
        final String user;
        final String processId;
        final int version;
        final Instant startedAt;
        final ReferenceEngine engine;
        ReferenceEngine.Run run;
        boolean cancelled;
        Instant finishedAt;
        final Set<String> announcedNodes = new HashSet<>();
        String announcedToken;

        State(String tenant, String user, String processId, int version, Instant startedAt, ReferenceEngine engine) {
            this.tenant = tenant;
            this.user = user;
            this.processId = processId;
            this.version = version;
            this.startedAt = startedAt;
            this.engine = engine;
        }
    }

    private final DefinitionStore store;
    private final WaitRegistry waits;
    private final Map<String, ServiceHandler.Connector> connectors;
    private final AgentInvoker invoker;
    private final EventBus bus;
    private final Clock clock;
    private IdempotencyStore idempotency;
    private tech.kayys.wayang.eip.ClaimStore claims = new tech.kayys.wayang.eip.ClaimStore.InMemory(10_000);
    private tech.kayys.wayang.eip.AggregationStore aggregation = new tech.kayys.wayang.eip.AggregationStore.InMemory(10_000);
    private final tech.kayys.wayang.eip.EipState eipState = new tech.kayys.wayang.eip.EipState();
    private final Map<String, State> runs = new LinkedHashMap<>();

    public ReferenceRunEngine(DefinitionStore store, WaitRegistry waits, Map<String, ServiceHandler.Connector> connectors,
            AgentInvoker invoker, EventBus bus, Clock clock) {
        this.store = store;
        this.waits = waits;
        // the actions an agent proposed run only in a release; here the step exists so a process that has it still runs (no agent runtime here records any)
        this.connectors = Map.copyOf(tech.kayys.wayang.embed.AgentActions.with(connectors, null, null));
        this.invoker = invoker;
        this.bus = bus;
        this.clock = clock;
        this.idempotency = new IdempotencyStore.InMemory(100_000);
    }

    /** Durable stores for EFFECTIVELY_ONCE pipelines, claim checks and the collect step (defaults are per-process memory). */
    public ReferenceRunEngine withEip(IdempotencyStore idem, tech.kayys.wayang.eip.ClaimStore claimStore,
            tech.kayys.wayang.eip.AggregationStore aggregationStore) {
        this.idempotency = idem;
        this.claims = claimStore;
        this.aggregation = aggregationStore;
        return this;
    }

    private tech.kayys.wayang.agent.ConversationMemory memory = new tech.kayys.wayang.agent.ConversationMemory.InMemory(10_000);

    /** Where agents with a {@code memory} option keep their conversations (default: this process's memory). */
    public ReferenceRunEngine withMemory(tech.kayys.wayang.agent.ConversationMemory m) {
        this.memory = m;
        return this;
    }

    private tech.kayys.wayang.runtime.SecretResolver secrets;

    /** Enables {@code ${secret:NAME}} resolution for service/agent/EIP nodes. */
    public ReferenceRunEngine withSecrets(tech.kayys.wayang.runtime.SecretResolver resolver) {
        this.secrets = resolver;
        return this;
    }

    private tech.kayys.wayang.runtime.ConfigResolver config;
    private tech.kayys.wayang.runtime.SecretAccessListener accessListener;

    /** Reports each {@code ${secret:NAME}} a node resolves (name only), for the audit log. */
    public ReferenceRunEngine withSecretAccessListener(tech.kayys.wayang.runtime.SecretAccessListener l) {
        this.accessListener = l;
        return this;
    }

    /** Enables {@code ${config:NAME}} resolution for service/agent/EIP nodes. */
    public ReferenceRunEngine withConfig(tech.kayys.wayang.runtime.ConfigResolver resolver) {
        this.config = resolver;
        return this;
    }

    @Override
    public RunView start(String tenantId, String userId, String processId, int version, ExecutionPlan plan,
            Map<String, Object> callerInput) {
        Map<String, Object> input = new LinkedHashMap<>(callerInput == null ? Map.of() : callerInput);
        input.put(tech.kayys.wayang.runtime.SecretResolver.TENANT_VARIABLE, tenantId); // never caller-controlled
        input.put(tech.kayys.wayang.runtime.SecretResolver.PROCESS_VARIABLE, processId); // likewise
        Map<String, DecisionModel> decisions = new LinkedHashMap<>();
        for (DefinitionSummary s : store.list(tenantId, DefinitionKind.DECISION)) {
            store.getLatest(tenantId, DefinitionKind.DECISION, s.id())
                    .ifPresent(d -> decisions.put(s.id(), DmnLoader.load(Studio.executable(d.document()))));
        }
        Map<String, AgentSpec> agents = new LinkedHashMap<>();
        for (DefinitionSummary s : store.list(tenantId, DefinitionKind.AGENT)) {
            store.getLatest(tenantId, DefinitionKind.AGENT, s.id())
                    .ifPresent(d -> agents.put(s.id(), AgentSpecLoader.fromMap(Studio.executable(d.document()))));
        }
        NodeRunner runner = new NodeRunner(List.of(new ControlHandler(waits, clock), new HumanHandler(waits),
                new DmnHandler(decisions), new EipHandler(idempotency, connectors, claims, aggregation, eipState), new AgentHandler(invoker, agents).withMemory(memory),
                new ServiceHandler(connectors)));
        runner.withProfile(tech.kayys.wayang.runtime.Profile.fromEnvironment());
        if (secrets != null) {
            runner.withSecrets(secrets);
        }
        if (config != null) {
            runner.withConfig(config);
        }
        if (accessListener != null) {
            runner.withSecretAccessListener(accessListener);
        }
        ReferenceEngine engine = new ReferenceEngine(runner, waits);
        State st = new State(tenantId, userId, processId, version, clock.instant(), engine);
        synchronized (this) {
            st.run = engine.start(plan, input);
            runs.put(st.run.id(), st);
            evict();
        }
        synchronized (st) {
            publish(st, RunEvent.Type.RUN_STARTED, null, Map.of("processId", processId, "version", version));
            afterAdvance(st);
            return view(st);
        }
    }

    @Override
    public synchronized Optional<RunView> get(String tenantId, String runId) {
        State st = runs.get(runId);
        return st == null || !st.tenant.equals(tenantId) ? Optional.empty() : Optional.of(view(st));
    }

    @Override
    public synchronized List<RunView> list(String tenantId, String processId, int limit) {
        List<State> all = new ArrayList<>();
        for (State s : runs.values()) {
            if (s.tenant.equals(tenantId) && (processId == null || s.processId.equals(processId))) {
                all.add(s);
            }
        }
        java.util.Collections.reverse(all); // insertion order = start order, so newest first
        List<RunView> out = new ArrayList<>();
        for (State s : all) {
            if (out.size() >= limit) {
                break;
            }
            out.add(view(s));
        }
        return out;
    }

    @Override
    public boolean completeWait(String tenantId, String token, Map<String, Object> outputs) {
        State target = null;
        synchronized (this) {
            for (State s : runs.values()) {
                ReferenceEngine.Run r = s.run;
                if (s.tenant.equals(tenantId) && !s.cancelled && r.status() == ReferenceEngine.Status.WAITING
                        && r.waitingOn() != null && r.waitingOn().token().equals(token)) {
                    target = s;
                    break;
                }
            }
        }
        if (target == null) {
            return false;
        }
        synchronized (target) {
            if (target.cancelled || target.run.waitingOn() == null || !target.run.waitingOn().token().equals(token)) {
                return false;
            }
            String nodeId = target.run.waitingOn().nodeId();
            target.engine.complete(target.run, token, outputs);
            publish(target, RunEvent.Type.TASK_COMPLETED, nodeId, Map.of("token", token));
            afterAdvance(target);
            return true;
        }
    }

    @Override
    public boolean cancel(String tenantId, String runId) {
        State st;
        synchronized (this) {
            st = runs.get(runId);
        }
        if (st == null || !st.tenant.equals(tenantId)) {
            return false;
        }
        synchronized (st) {
            if (st.cancelled || st.run.status() != ReferenceEngine.Status.WAITING) {
                return false; // only a parked run can be cancelled; this engine finishes everything else synchronously
            }
            waits.take(st.run.waitingOn().token());
            st.cancelled = true;
            st.finishedAt = clock.instant();
            publish(st, RunEvent.Type.RUN_CANCELLED, null, Map.of());
            return true;
        }
    }

    @Override
    public int fireDueTimers() {
        int fired = 0;
        for (WaitRegistry.Registered r : waits.due(clock.instant())) {
            String tenant;
            synchronized (this) {
                State st = runs.get(r.runId());
                tenant = st == null ? null : st.tenant;
            }
            if (tenant != null && completeWait(tenant, r.ticket().token(),
                    Map.of("firedAt", clock.instant().toString(), "dueAt", r.ticket().dueAt().toString()))) {
                fired++;
            }
        }
        return fired;
    }

    // ------------------------------------------------------------------ internals

    private void afterAdvance(State st) {
        ReferenceEngine.Run r = st.run;
        String waitingNode = r.waitingOn() == null ? null : r.waitingOn().nodeId();
        for (String n : r.executedNodes()) {
            if (!n.equals(waitingNode) && st.announcedNodes.add(n)) {
                publish(st, RunEvent.Type.NODE_COMPLETED, n, Map.of());
            }
        }
        if (r.status() == ReferenceEngine.Status.WAITING && r.waitingOn() != null
                && !r.waitingOn().token().equals(st.announcedToken)) {
            st.announcedToken = r.waitingOn().token();
            publish(st, RunEvent.Type.TASK_CREATED, waitingNode, Map.of("token", st.announcedToken, "kind", r.waitingOn().kind()));
        }
        if (r.status() == ReferenceEngine.Status.COMPLETED && st.finishedAt == null) {
            st.finishedAt = clock.instant();
            publish(st, RunEvent.Type.RUN_COMPLETED, null, Map.of());
        } else if (r.status() == ReferenceEngine.Status.FAILED && st.finishedAt == null) {
            st.finishedAt = clock.instant();
            publish(st, RunEvent.Type.RUN_FAILED, null, Map.of("error", String.valueOf(r.error())));
        }
    }

    private void publish(State st, RunEvent.Type t, String nodeId, Map<String, Object> data) {
        bus.publish(new RunEvent(t, st.tenant, st.run.id(), nodeId, clock.instant(), data));
    }

    private RunView view(State st) {
        ReferenceEngine.Run r = st.run;
        RunStatus status = st.cancelled ? RunStatus.CANCELLED : switch (r.status()) {
            case COMPLETED -> RunStatus.COMPLETED;
            case WAITING -> RunStatus.WAITING;
            case FAILED -> RunStatus.FAILED;
        };
        List<TaskView> waiting = new ArrayList<>();
        if (!st.cancelled && r.waitingOn() != null) {
            waiting.add(task(r.id(), r.waitingOn()));
        }
        return new RunView(r.id(), st.tenant, st.processId, st.version, status, r.variables(), r.executedNodes(), waiting,
                r.error(), st.user, st.startedAt, st.finishedAt);
    }

    static TaskView task(String runId, WaitTicket t) {
        Object title = t.info().get("title");
        Object assignee = t.info().get("assignee");
        return new TaskView(t.token(), runId, t.nodeId(), t.kind(), title == null ? null : String.valueOf(title),
                assignee == null ? null : String.valueOf(assignee), t.info(), t.dueAt(), t.messageName(), t.correlationKey());
    }

    private void evict() {
        if (runs.size() <= MAX_RETAINED_RUNS) {
            return;
        }
        var it = runs.entrySet().iterator();
        while (it.hasNext() && runs.size() > MAX_RETAINED_RUNS) {
            State s = it.next().getValue();
            if (s.cancelled || s.run.status() != ReferenceEngine.Status.WAITING) {
                it.remove();
            }
        }
    }
}
