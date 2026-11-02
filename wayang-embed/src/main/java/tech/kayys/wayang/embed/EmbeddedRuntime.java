package tech.kayys.wayang.embed;

import java.time.Clock;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.TreeSet;

import tech.kayys.wayang.agent.AgentInvoker;
import tech.kayys.wayang.agent.AgentSpec;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.dmn.DecisionModel;
import tech.kayys.wayang.eip.AggregationStore;
import tech.kayys.wayang.eip.ClaimStore;
import tech.kayys.wayang.eip.EipState;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.runtime.AgentHandler;
import tech.kayys.wayang.runtime.ConfigResolver;
import tech.kayys.wayang.runtime.ControlHandler;
import tech.kayys.wayang.runtime.DmnHandler;
import tech.kayys.wayang.runtime.EipHandler;
import tech.kayys.wayang.runtime.HumanHandler;
import tech.kayys.wayang.runtime.Extensions;
import tech.kayys.wayang.runtime.NodeRunner;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ReferenceEngine;
import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.WaitTicket;

/**
 * Runs the processes of one release bundle, for one tenant ({@link #TENANT}). Everything is compiled and checked at construction,
 * so a release that cannot run fails to start instead of failing on the first request. Runs live in memory (bounded): they are
 * lost on restart, so this fits short, stateless workflows and parked human/timer waits that you can afford to lose; for durable
 * runs use the Gamelan engine. Each run is processed under its own lock.
 */
public final class EmbeddedRuntime implements ReleaseEngine {

    private static final int MAX_RETAINED_RUNS = 5_000;
    private static final int MAX_WAITING = 5_000;

    private static final class State {
        final String processId;
        final Instant startedAt;
        ReferenceEngine.Run run;
        boolean cancelled;
        Instant finishedAt;

        State(String processId, Instant startedAt) {
            this.processId = processId;
            this.startedAt = startedAt;
        }
    }

    private final Map<String, ExecutionPlan> plans = new LinkedHashMap<>();
    private final Map<String, Integer> versions = new LinkedHashMap<>();
    private final WaitRegistry waits = new WaitRegistry.InMemory();
    private final ReferenceEngine engine;
    private final Clock clock;
    private final ReleaseBundle bundle;
    private final SecretResolver secrets;
    private final tech.kayys.wayang.eip.CollectSweeper sweeper;
    private final Map<String, State> runs = new LinkedHashMap<>();

    /**
     * @param connectors the connectors the release uses (the generated {@code App} builds only those it needs)
     * @param invoker    runs agent nodes; null when the bundle has none, otherwise required
     */
    public EmbeddedRuntime(ReleaseBundle bundle, Map<String, ServiceHandler.Connector> connectors, AgentInvoker invoker, SecretResolver secrets,
            ConfigResolver config, Clock clock) {
        this(bundle, connectors, invoker, secrets, config, clock, null);
    }

    /** @param memory what agents with a {@code memory} option remember between runs; null keeps it in memory only */
    public EmbeddedRuntime(ReleaseBundle bundle, Map<String, ServiceHandler.Connector> connectors, AgentInvoker invoker, SecretResolver secrets,
            ConfigResolver config, Clock clock, tech.kayys.wayang.agent.ConversationMemory memory) {
        this.bundle = bundle;
        this.clock = clock;
        this.secrets = secrets;
        ReleaseDefinitions defs = new ReleaseDefinitions(bundle);
        plans.putAll(defs.plans());
        Profile profile = Profile.fromEnvironment();
        Extensions ext = Extensions.load(profile);
        for (ExecutionPlan plan : plans.values()) {
            List<String> bad = profile.violations(plan);
            if (!bad.isEmpty()) {
                throw new IllegalStateException("Process '" + plan.processId() + "' does not fit the '" + profile
                        + "' profile: " + String.join("; ", bad));
            }
        }
        versions.putAll(defs.versions());
        Map<String, DecisionModel> decisions = defs.decisions();
        Map<String, AgentSpec> agents = defs.agents();
        AggregationStore aggregation = new AggregationStore.InMemory(10_000);
        Map<String, Map<String, Object>> processDocs = new LinkedHashMap<>();
        bundle.definitions().stream().filter(d -> d.kind().equals("PROCESS")).forEach(d -> processDocs.put(d.id(), d.document()));
        this.sweeper = new tech.kayys.wayang.eip.CollectSweeper(aggregation,
                (tenant, process, node) -> TENANT.equals(tenant) ? tech.kayys.wayang.eip.CollectSweeper.find(tenant, processDocs.get(process), node) : null,
                (tenant, process, input) -> start(process, input), clock, null);
        Map<String, ServiceHandler.Connector> all = AgentActions.with(ext.mergeConnectors(connectors == null ? Map.of() : connectors), secrets, config);
        AgentInvoker inv = invoker != null ? invoker : (spec, input) -> {
            throw new IllegalStateException("No agent runtime is configured in this release");
        };
        NodeRunner runner = new NodeRunner(List.of(new ControlHandler(waits, clock), new HumanHandler(waits), new DmnHandler(decisions),
                new EipHandler(new IdempotencyStore.InMemory(100_000), all, new ClaimStore.InMemory(10_000),
                        aggregation, new EipState()),
                new AgentHandler(inv, agents).withMemory(memory != null ? memory : new tech.kayys.wayang.agent.ConversationMemory.InMemory(10_000)),
                new ServiceHandler(all)));
        runner.withProfile(profile).withExtensions(ext);
        if (secrets != null) {
            runner.withSecrets(secrets);
        }
        if (config != null) {
            runner.withConfig(config);
        }
        this.engine = new ReferenceEngine(runner, waits);
    }

    @Override
    public ReleaseBundle bundle() {
        return bundle;
    }

    @Override
    public Set<String> processIds() {
        return Collections.unmodifiableSet(new TreeSet<>(plans.keySet()));
    }

    @Override
    public int version(String processId) {
        return versions.getOrDefault(processId, 0);
    }

    /** Names this release needs that nothing provides right now (names only, never values), for a readiness check. */
    @Override
    public List<String> missingSecrets() {
        List<String> missing = new ArrayList<>();
        if (secrets != null) {
            for (String n : bundle.requiredSecrets()) {
                if (secrets.resolve(TENANT, n) == null) {
                    missing.add(n);
                }
            }
        } else {
            missing.addAll(bundle.requiredSecrets());
        }
        return missing;
    }

    /** @throws IllegalArgumentException when there is no such process */
    @Override
    public RunInfo start(String processId, Map<String, Object> callerInput) {
        ExecutionPlan plan = plans.get(processId);
        if (plan == null) {
            throw new IllegalArgumentException("No such process: " + processId);
        }
        Map<String, Object> input = new LinkedHashMap<>(callerInput == null ? Map.of() : callerInput);
        input.put(SecretResolver.TENANT_VARIABLE, TENANT); // never caller-controlled
        input.put(SecretResolver.PROCESS_VARIABLE, processId);
        State st = new State(processId, clock.instant());
        synchronized (this) {
            if (waitingCount() >= MAX_WAITING) {
                throw new IllegalStateException("Too many parked runs");
            }
            st.run = engine.start(plan, input);
            runs.put(st.run.id(), st);
            evict();
        }
        synchronized (st) {
            finishIfDone(st);
            return view(st);
        }
    }

    @Override
    public synchronized Optional<RunInfo> get(String runId) {
        State st = runs.get(runId);
        return st == null ? Optional.empty() : Optional.of(view(st));
    }

    /** Newest first. */
    @Override
    public synchronized List<RunInfo> list(String processId, int limit) {
        List<State> all = new ArrayList<>();
        for (State s : runs.values()) {
            if (processId == null || s.processId.equals(processId)) {
                all.add(s);
            }
        }
        Collections.reverse(all);
        List<RunInfo> out = new ArrayList<>();
        for (State s : all) {
            if (out.size() >= Math.max(1, limit)) {
                break;
            }
            out.add(view(s));
        }
        return out;
    }

    /** @return false when no run is waiting on that token */
    @Override
    public boolean completeWait(String token, Map<String, Object> outputs) {
        State target = null;
        synchronized (this) {
            for (State s : runs.values()) {
                ReferenceEngine.Run r = s.run;
                if (!s.cancelled && r.status() == ReferenceEngine.Status.WAITING && r.waitingOn() != null && r.waitingOn().token().equals(token)) {
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
            engine.complete(target.run, token, outputs == null ? Map.of() : outputs);
            finishIfDone(target);
            return true;
        }
    }

    @Override
    public boolean cancel(String runId) {
        State st;
        synchronized (this) {
            st = runs.get(runId);
        }
        if (st == null) {
            return false;
        }
        synchronized (st) {
            if (st.cancelled || st.run.status() != ReferenceEngine.Status.WAITING) {
                return false;
            }
            waits.take(st.run.waitingOn().token());
            st.cancelled = true;
            st.finishedAt = clock.instant();
            return true;
        }
    }

    /** Completes every due timer wait and closes collect groups past their deadline. @return how many fired or were closed */
    @Override
    public int fireDueTimers() {
        int fired = sweeper.sweep();
        for (WaitRegistry.Registered r : waits.due(clock.instant())) {
            if (completeWait(r.ticket().token(), Map.of("firedAt", clock.instant().toString(), "dueAt", String.valueOf(r.ticket().dueAt())))) {
                fired++;
            }
        }
        return fired;
    }

    // ------------------------------------------------------------------ internals

    private int waitingCount() {
        int n = 0;
        for (State s : runs.values()) {
            if (!s.cancelled && s.run.status() == ReferenceEngine.Status.WAITING) {
                n++;
            }
        }
        return n;
    }

    private void finishIfDone(State st) {
        if (st.finishedAt == null && st.run.status() != ReferenceEngine.Status.WAITING) {
            st.finishedAt = clock.instant();
        }
    }

    private RunInfo view(State st) {
        ReferenceEngine.Run r = st.run;
        String status = st.cancelled ? "CANCELLED" : r.status().name();
        List<Map<String, Object>> waiting = new ArrayList<>();
        if (!st.cancelled && r.waitingOn() != null) {
            WaitTicket t = r.waitingOn();
            Map<String, Object> w = new LinkedHashMap<>();
            w.put("token", t.token());
            w.put("nodeId", t.nodeId());
            w.put("kind", t.kind());
            if (t.info().get("title") != null) {
                w.put("title", String.valueOf(t.info().get("title")));
            }
            if (t.dueAt() != null) {
                w.put("dueAt", t.dueAt().toString());
            }
            if (t.messageName() != null) {
                w.put("message", t.messageName());
            }
            waiting.add(w);
        }
        Map<String, Object> vars = new LinkedHashMap<>();
        r.variables().forEach((k, v) -> {
            if (!k.startsWith("wayang.")) {
                vars.put(k, v);
            }
        });
        return new RunInfo(r.id(), st.processId, status, vars, List.copyOf(r.executedNodes()), waiting, r.error(), st.startedAt, st.finishedAt);
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
