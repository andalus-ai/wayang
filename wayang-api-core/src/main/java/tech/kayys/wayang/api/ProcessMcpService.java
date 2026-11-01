package tech.kayys.wayang.api;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.mcp.McpServerCore;
import tech.kayys.wayang.mcp.McpTool;
import tech.kayys.wayang.mcp.SchemaCheck;
import tech.kayys.wayang.mcp.ToolResult;

/**
 * Publishes the tenant's processes as MCP tools, so any MCP client or agent can start a workflow. Nothing is exposed
 * unless the process's latest published version has {@code studio.mcp.expose = true}. Listing needs DEFINITION_READ,
 * running a tool needs RUN_START, and the run's variables are only returned to callers who also have RUN_READ.
 *
 * <p>Tool {@code run_<processId>}: the arguments become the run's input variables, checked against
 * {@code studio.mcp.inputSchema}. It waits up to {@code studio.mcp.waitSeconds} (default 20) for the run to finish; a
 * run parked on a human task or timer returns its {@code runId} and status, and {@code get_run} reads it later.
 */
public final class ProcessMcpService {

    public static final int MAX_TOOLS = 100;
    private static final int DEFAULT_WAIT = 20;
    private static final long POLL_MS = 50;

    private final WayangApi api;
    private final DefinitionStore store;
    private final long waitScaleMs;

    public ProcessMcpService(WayangApi api, DefinitionStore store) {
        this(api, store, 1000);
    }

    /** {@code waitScaleMs}: milliseconds per configured wait second (tests shrink it). */
    ProcessMcpService(WayangApi api, DefinitionStore store, long waitScaleMs) {
        this.api = api;
        this.store = store;
        this.waitScaleMs = waitScaleMs;
    }

    private record Exposed(McpTool tool, String processId, int waitSeconds) {
    }

    public String rpc(Principal p, String body) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.canAnywhere(Permission.DEFINITION_READ)) {
            throw ApiException.forbidden(Permission.DEFINITION_READ);
        }
        List<Exposed> exposed = exposed(p);
        List<McpTool> tools = new ArrayList<>();
        tools.add(getRunTool());
        exposed.forEach(e -> tools.add(e.tool()));
        Map<String, Exposed> byName = new LinkedHashMap<>();
        exposed.forEach(e -> byName.put(e.tool().name(), e));
        McpServerCore core = new McpServerCore("wayang-processes", "1",
                "Each run_* tool starts a published Wayang process and returns its status; get_run reads a run later.", tools,
                (tool, args) -> invoke(p, tool, byName.get(tool.name()), args));
        return core.handle(body);
    }

    /** Names and ids of the processes currently exposed (for the Studio and tests). */
    public List<Map<String, Object>> list(Principal p) {
        if (p == null || !p.canAnywhere(Permission.DEFINITION_READ)) {
            throw ApiException.forbidden(Permission.DEFINITION_READ);
        }
        List<Map<String, Object>> out = new ArrayList<>();
        for (Exposed e : exposed(p)) {
            out.add(Map.of("tool", e.tool().name(), "processId", e.processId(), "description", e.tool().description()));
        }
        return out;
    }

    private List<Exposed> exposed(Principal p) {
        List<Exposed> out = new ArrayList<>();
        Set<String> names = new LinkedHashSet<>();
        for (DefinitionSummary s : store.list(p.tenantId(), DefinitionKind.PROCESS)) {
            if (out.size() >= MAX_TOOLS) {
                break;
            }
            if (s.latestVersion() < 1) {
                continue;
            }
            StoredDefinition d = store.getLatest(p.tenantId(), DefinitionKind.PROCESS, s.id()).orElse(null);
            if (d == null) {
                continue;
            }
            if (!p.can(Permission.DEFINITION_READ, Studio.projectId(d.document()))) {
                continue; // not visible to a project-scoped caller
            }
            Map<String, Object> cfg = Studio.mcp(d.document());
            if (!Boolean.TRUE.equals(cfg.get("expose"))) {
                continue;
            }
            String name = unique(names, "run_" + s.id().replaceAll("[^A-Za-z0-9_-]", "_"));
            String desc = cfg.get("description") instanceof String ds && !ds.isBlank() ? ds
                    : "Start the process '" + (s.name() == null || s.name().isBlank() ? s.id() : s.name()) + "'";
            @SuppressWarnings("unchecked")
            Map<String, Object> schema = cfg.get("inputSchema") instanceof Map<?, ?> m ? (Map<String, Object>) m
                    : Map.of("type", "object", "additionalProperties", true);
            int wait = cfg.get("waitSeconds") instanceof Number n ? n.intValue() : DEFAULT_WAIT;
            out.add(new Exposed(new McpTool(name, desc, schema, null, new McpTool.Hints(false, false, false), null), s.id(), wait));
        }
        return out;
    }

    private static String unique(Set<String> used, String base) {
        String b = base.length() > 60 ? base.substring(0, 60) : base;
        String n = b;
        int i = 2;
        while (!used.add(n)) {
            n = b + "_" + i++;
        }
        return n;
    }

    private static McpTool getRunTool() {
        return new McpTool("get_run", "Read the status, variables and error of a run started by a run_* tool",
                Map.of("type", "object", "properties", Map.of("runId", Map.of("type", "string")), "required", List.of("runId"),
                        "additionalProperties", false),
                null, new McpTool.Hints(true, false, true), null);
    }

    private ToolResult invoke(Principal p, McpTool tool, Exposed e, Map<String, Object> args) {
        try {
            if (tool.name().equals("get_run")) {
                if (!(args.get("runId") instanceof String id) || id.isBlank()) {
                    return ToolResult.error("runId is required");
                }
                RunView r = api.getRun(p, id);
                return result(p, r, canReadRun(p, r));
            }
            if (e == null) {
                return ToolResult.error("Unknown tool");
            }
            List<String> problems = SchemaCheck.validate(tool.inputSchema(), args);
            if (!problems.isEmpty()) {
                return ToolResult.error("Invalid arguments: " + String.join("; ", problems));
            }
            RunView run = api.startRun(p, e.processId(), null, args);
            long deadline = System.currentTimeMillis() + (long) e.waitSeconds() * waitScaleMs;
            while (run.status() == RunStatus.RUNNING && System.currentTimeMillis() < deadline) {
                try {
                    Thread.sleep(POLL_MS);
                } catch (InterruptedException ie) {
                    Thread.currentThread().interrupt();
                    break;
                }
                run = api.getRun(p, run.id());
            }
            return result(p, run, canReadRun(p, run));
        } catch (ApiException ex) {
            return ToolResult.error(ex.getMessage());
        }
    }

    private boolean canReadRun(Principal p, RunView run) {
        return p.can(Permission.RUN_READ) || store.getVersion(p.tenantId(), DefinitionKind.PROCESS, run.processId(), run.version())
                .map(d -> p.can(Permission.RUN_READ, Studio.projectId(d.document()))).orElse(false);
    }

    private static ToolResult result(Principal p, RunView run, boolean mayRead) {
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("runId", run.id());
        out.put("process", run.processId());
        out.put("status", run.status().name());
        if (mayRead) {
            out.put("variables", run.variables());
            if (run.error() != null) {
                out.put("error", run.error());
            }
            if (!run.waiting().isEmpty()) {
                out.put("waitingFor", run.waiting().size() + " human task(s) or message(s); the run continues when they are completed");
            }
        }
        boolean failed = run.status() == RunStatus.FAILED || run.status() == RunStatus.CANCELLED;
        String text = tech.kayys.wayang.runtime.json.Json.write(out);
        return failed ? new ToolResult(true, text, out) : ToolResult.ok(text, out);
    }
}
