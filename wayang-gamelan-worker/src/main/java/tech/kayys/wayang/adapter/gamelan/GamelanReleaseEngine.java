package tech.kayys.wayang.adapter.gamelan;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tech.kayys.gamelan.engine.execution.ExecutionHistory;
import tech.kayys.gamelan.engine.node.NodeExecutionRecord;
import tech.kayys.gamelan.engine.node.NodeExecutionStatus;
import tech.kayys.gamelan.engine.run.RunResponse;
import tech.kayys.gamelan.sdk.client.GamelanClient;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.embed.ReleaseBundle;
import tech.kayys.wayang.embed.ReleaseDefinitions;
import tech.kayys.wayang.embed.ReleaseEngine;
import tech.kayys.wayang.runtime.SecretResolver;
import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.json.Json;

/**
 * {@link ReleaseEngine} on Gamelan: the runs are Gamelan's (durable, they survive a restart of this process), the node work is
 * done by the Wayang executors in this same JVM, and human/timer/message waits are parked here, so the process holding them must
 * be the one that is asked to complete them: run <b>one replica</b> when a workflow waits.
 *
 * <p>The definitions are deployed under {@code release~<process>} with a version of the form {@code <published>-<hash>}, where
 * the hash covers the process document, so a re-release with changed content never silently runs the older definition that
 * Gamelan already holds under the same number. Not exercised against a live Gamelan: only against an in-memory fake of its client.
 */
public final class GamelanReleaseEngine implements ReleaseEngine {

    private static final Duration CALL_TIMEOUT = Duration.ofSeconds(30);
    private static final String PREFIX = TENANT + "~";

    private final ReleaseBundle bundle;
    private final ReleaseDefinitions defs;
    private final GamelanClient gamelan;
    private final WayangRuntime runtime;
    private final SecretResolver secrets;
    private final Clock clock;
    private final Map<String, String> versions = new LinkedHashMap<>();
    private volatile boolean deployed;
    private volatile tech.kayys.wayang.eip.CollectSweeper sweeper;

    public GamelanReleaseEngine(ReleaseBundle bundle, ReleaseDefinitions defs, GamelanClient gamelan, WayangRuntime runtime, SecretResolver secrets,
            Clock clock) {
        this.bundle = bundle;
        this.defs = defs;
        this.gamelan = gamelan;
        this.runtime = runtime;
        this.secrets = secrets;
        this.clock = clock;
        for (ReleaseBundle.Definition d : bundle.definitions()) {
            if (d.kind().equals("PROCESS")) {
                versions.put(d.id(), d.version() + "-" + hash(d.document()));
            }
        }
    }

    /**
     * Turns on hard deadlines for {@code collect} groups with {@code sweepTo}: {@link #fireDueTimers()} then also closes the groups
     * of {@code aggregation} that are past their timeout and starts the named process on Gamelan. {@code aggregation} must be the store the
     * release's runtime uses ({@link ReleaseComponents#aggregationStore()}). Groups live in this JVM, like the parked waits.
     */
    public GamelanReleaseEngine withCollectSweeping(tech.kayys.wayang.eip.AggregationStore aggregation) {
        Map<String, Map<String, Object>> docs = new LinkedHashMap<>();
        bundle.definitions().stream().filter(d -> d.kind().equals("PROCESS")).forEach(d -> docs.put(d.id(), d.document()));
        this.sweeper = new tech.kayys.wayang.eip.CollectSweeper(aggregation,
                (tenant, process, node) -> TENANT.equals(tenant) ? tech.kayys.wayang.eip.CollectSweeper.find(tenant, docs.get(process), node) : null,
                (tenant, process, input) -> start(process, input), clock, null);
        return this;
    }

    static String definitionId(String processId) {
        return PREFIX + processId;
    }

    /** The Gamelan version string a process is deployed under. */
    public String gamelanVersion(String processId) {
        return versions.get(processId);
    }

    /**
     * Registers every process with Gamelan; one that is already there under the same version is left alone.
     *
     * @throws Unavailable when Gamelan cannot be reached or refuses a definition
     */
    public void deploy() {
        for (Map.Entry<String, ExecutionPlan> e : defs.plans().entrySet()) {
            ExecutionPlan q = TenantQualifier.apply(e.getValue(), TENANT);
            ExecutionPlan named = new ExecutionPlan(definitionId(e.getKey()), q.processName(), q.nodes());
            try {
                gamelan.workflows().create(GamelanPlanMapper.toDefinition(named, TENANT, versions.get(e.getKey()))).await().atMost(CALL_TIMEOUT);
            } catch (RuntimeException ex) {
                if (!looksLikeAlreadyExists(ex)) {
                    throw new Unavailable("Cannot deploy process " + e.getKey() + " to Gamelan");
                }
            }
        }
        deployed = true;
    }

    /** Ready once every definition is on Gamelan: until then a start would fail. */
    @Override
    public boolean ready() {
        return deployed;
    }

    /** {@link #deploy()} again and again until it works or the time is up (Gamelan may start after this process). */
    public void deployWithRetry(Duration total, Duration pause) throws InterruptedException {
        long until = clock.millis() + total.toMillis();
        while (true) {
            try {
                deploy();
                return;
            } catch (Unavailable e) {
                if (clock.millis() >= until) {
                    throw e;
                }
                Thread.sleep(pause.toMillis());
            }
        }
    }

    @Override
    public ReleaseBundle bundle() {
        return bundle;
    }

    @Override
    public Set<String> processIds() {
        return defs.plans().keySet();
    }

    @Override
    public int version(String processId) {
        return defs.versions().getOrDefault(processId, 0);
    }

    @Override
    public List<String> missingSecrets() {
        List<String> missing = new ArrayList<>();
        for (String n : bundle.requiredSecrets()) {
            if (secrets == null || secrets.resolve(TENANT, n) == null) {
                missing.add(n);
            }
        }
        return missing;
    }

    @Override
    public RunInfo start(String processId, Map<String, Object> callerInput) {
        if (!defs.plans().containsKey(processId)) {
            throw new IllegalArgumentException("No such process: " + processId);
        }
        Map<String, Object> input = new LinkedHashMap<>(callerInput == null ? Map.of() : callerInput);
        input.put(SecretResolver.TENANT_VARIABLE, TENANT); // never caller-controlled
        input.put(SecretResolver.PROCESS_VARIABLE, processId);
        try {
            RunResponse r = gamelan.runs().create(definitionId(processId)).version(versions.get(processId)).inputs(input)
                    .label("wayang.process", processId).executeAndStart().await().atMost(CALL_TIMEOUT);
            return view(r, null);
        } catch (RuntimeException e) {
            throw new Unavailable("Cannot start the run on Gamelan");
        }
    }

    @Override
    public Optional<RunInfo> get(String runId) {
        try {
            return fetch(runId).map(s -> view(s.run, s.history));
        } catch (RuntimeException e) {
            throw new Unavailable("Cannot read the run from Gamelan");
        }
    }

    @Override
    public List<RunInfo> list(String processId, int limit) {
        try {
            var q = gamelan.runs().query().size(Math.max(1, limit));
            if (processId != null) {
                q.workflowId(definitionId(processId));
            }
            List<RunInfo> out = new ArrayList<>();
            for (RunResponse r : q.execute().await().atMost(CALL_TIMEOUT)) {
                if (r.getWorkflowId() != null && r.getWorkflowId().startsWith(PREFIX)) {
                    out.add(view(r, null));
                }
            }
            return out;
        } catch (RuntimeException e) {
            throw new Unavailable("Cannot list runs on Gamelan");
        }
    }

    @Override
    public boolean completeWait(String token, Map<String, Object> outputs) {
        var reg = runtime.registry().find(token);
        if (reg.isEmpty()) {
            return false;
        }
        return runtime.completeWait(token, outputs);
    }

    @Override
    public boolean cancel(String runId) {
        Optional<Snap> r;
        try {
            r = fetch(runId);
        } catch (RuntimeException e) {
            throw new Unavailable("Cannot read the run from Gamelan");
        }
        if (r.isEmpty() || terminal(status(r.get().run.getStatus(), false))) {
            return false;
        }
        for (WaitRegistry.Registered w : runtime.registry().listByRun(runId)) {
            runtime.cancelWait(w.ticket().token(), "Run cancelled");
        }
        try {
            gamelan.runs().cancel(runId, "Cancelled through the release API").await().atMost(CALL_TIMEOUT);
        } catch (RuntimeException e) {
            throw new Unavailable("Cannot cancel the run on Gamelan");
        }
        return true;
    }

    @Override
    public int fireDueTimers() {
        int n = runtime.fireDueTimers();
        var sw = sweeper;
        return sw == null ? n : n + sw.sweep();
    }

    // ------------------------------------------------------------------ mapping

    private record Snap(RunResponse run, ExecutionHistory history) {
    }

    /** Empty when the run is missing or is not one of this release's processes. */
    private Optional<Snap> fetch(String runId) {
        try {
            RunResponse r = gamelan.runs().get(runId).await().atMost(CALL_TIMEOUT);
            if (r == null || r.getWorkflowId() == null || !r.getWorkflowId().startsWith(PREFIX)) {
                return Optional.empty();
            }
            return Optional.of(new Snap(r, gamelan.runs().getHistory(runId).await().atMost(CALL_TIMEOUT)));
        } catch (RuntimeException e) {
            String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
            if (m.contains("not found") || m.contains("404")) {
                return Optional.empty();
            }
            throw e;
        }
    }

    private RunInfo view(RunResponse r, ExecutionHistory history) {
        List<Map<String, Object>> waiting = new ArrayList<>();
        for (WaitRegistry.Registered w : runtime.registry().listByRun(r.getRunId())) {
            var t = w.ticket();
            Map<String, Object> m = new LinkedHashMap<>();
            m.put("token", t.token());
            m.put("nodeId", t.nodeId());
            m.put("kind", t.kind());
            if (t.info().get("title") != null) {
                m.put("title", String.valueOf(t.info().get("title")));
            }
            if (t.dueAt() != null) {
                m.put("dueAt", t.dueAt().toString());
            }
            if (t.messageName() != null) {
                m.put("message", t.messageName());
            }
            waiting.add(m);
        }
        String status = status(r.getStatus(), !waiting.isEmpty());
        if (terminal(status)) {
            waiting.clear(); // stale tickets of a finished run are not actionable
        }
        String def = r.getWorkflowId();
        String processId = def.startsWith(PREFIX) ? def.substring(PREFIX.length()) : def;
        Map<String, Object> vars = new LinkedHashMap<>();
        if (r.getOutputs() != null) {
            r.getOutputs().forEach((k, v) -> {
                if (!k.startsWith("wayang.")) {
                    vars.put(k, v);
                }
            });
        }
        List<String> executed = new ArrayList<>();
        String error = status.equals("FAILED") ? r.getErrorMessage() : null;
        if (history != null && history.getNodeExecutions() != null) {
            for (NodeExecutionRecord n : history.getNodeExecutions()) {
                if (done(n.getStatus())) {
                    executed.add(n.getNodeId());
                    if (n.getOutputs() != null) {
                        n.getOutputs().forEach((k, v) -> vars.putIfAbsent(n.getNodeId() + "." + k, v));
                    }
                }
                if (status.equals("FAILED") && error == null && n.getStatus() == NodeExecutionStatus.FAILED && n.getError() != null) {
                    error = n.getNodeId() + ": " + n.getError();
                }
            }
        }
        if (status.equals("FAILED") && error == null) {
            error = "Run failed";
        }
        Instant started = r.getStartedAt() != null ? r.getStartedAt() : r.getCreatedAt() != null ? r.getCreatedAt() : clock.instant();
        return new RunInfo(r.getRunId(), processId, status, vars, executed, waiting, error, started, r.getCompletedAt());
    }

    static String status(String gamelan, boolean parked) {
        if (gamelan == null) {
            return "RUNNING";
        }
        return switch (gamelan) {
            case "COMPLETED" -> "COMPLETED";
            case "FAILED", "COMPENSATED" -> "FAILED";
            case "CANCELLED" -> "CANCELLED";
            case "SUSPENDED" -> "WAITING";
            default -> parked ? "WAITING" : "RUNNING";
        };
    }

    private static boolean terminal(String s) {
        return s.equals("COMPLETED") || s.equals("FAILED") || s.equals("CANCELLED");
    }

    private static boolean done(NodeExecutionStatus s) {
        return s == NodeExecutionStatus.COMPLETED || s == NodeExecutionStatus.SUCCESS || s == NodeExecutionStatus.FAILED
                || s == NodeExecutionStatus.CANCELLED || s == NodeExecutionStatus.SKIPPED;
    }

    private static boolean looksLikeAlreadyExists(Throwable e) {
        String m = String.valueOf(e.getMessage()).toLowerCase(Locale.ROOT);
        return m.contains("already exist") || m.contains("duplicate") || m.contains("409") || m.contains("conflict");
    }

    private static String hash(Map<String, Object> document) {
        try {
            byte[] d = MessageDigest.getInstance("SHA-256").digest(Json.write(ReleaseDefinitions.withoutStudio(document)).getBytes(StandardCharsets.UTF_8));
            return HexFormat.of().formatHex(d).substring(0, 8);
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }
}
