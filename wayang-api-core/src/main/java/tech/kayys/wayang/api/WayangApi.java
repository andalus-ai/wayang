package tech.kayys.wayang.api;

import tech.kayys.wayang.api.definition.AutoLayout;
import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.definition.NodeCatalog;
import tech.kayys.wayang.api.project.Project;
import tech.kayys.wayang.api.project.ProjectStore;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.api.run.RunEngine;
import tech.kayys.wayang.api.run.RunEvent;
import tech.kayys.wayang.api.run.RunView;
import tech.kayys.wayang.api.definition.StoredDefinition;
import tech.kayys.wayang.api.definition.Studio;
import tech.kayys.wayang.api.run.TaskView;
import tech.kayys.wayang.api.definition.ValidationReport;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.function.Consumer;
import java.util.regex.Pattern;

import tech.kayys.wayang.agent.AgentSpecLoader;
import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ExecutionPlan;
import tech.kayys.wayang.bpmn.Issue;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessMaps;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.bpmn.xml.BpmnXml;
import tech.kayys.wayang.dmn.DmnLoader;
import tech.kayys.wayang.eip.EipLoader;
import tech.kayys.wayang.eip.IdempotencyStore;
import tech.kayys.wayang.expression.Expressions;
import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Transport-neutral application service behind REST, WebSocket and gRPC.
 * Every method takes the authenticated {@link Principal}, checks its
 * permission, and scopes data to the principal's tenant. Transports only
 * translate; they never decide.
 */
public final class WayangApi {

    public static final int MAX_DOCUMENT_CHARS = 2_000_000;
    public static final int MAX_LIST = 500;
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_.-]{1,100}");

    private final DefinitionStore store;
    private final ProjectStore projects;
    private final RunEngine engine;
    private final WaitRegistry waits;
    private final EventBus bus;
    private final Clock clock;

    private volatile tech.kayys.wayang.runtime.Profile profile = tech.kayys.wayang.runtime.Profile.fromEnvironment();

    /** Which features this deployment serves: hides the rest from the palette and refuses to save them. */
    public WayangApi withProfile(tech.kayys.wayang.runtime.Profile profile) {
        this.profile = profile;
        return this;
    }

    public tech.kayys.wayang.runtime.Profile profile() {
        return profile;
    }

    public WayangApi(DefinitionStore store, RunEngine engine, WaitRegistry waits, EventBus bus, Clock clock) {
        this(store, new ProjectStore.InMemory(), engine, waits, bus, clock);
    }

    public WayangApi(DefinitionStore store, ProjectStore projects, RunEngine engine, WaitRegistry waits, EventBus bus,
            Clock clock) {
        this.store = store;
        this.projects = projects;
        this.engine = engine;
        this.waits = waits;
        this.bus = bus;
        this.clock = clock;
    }

    // ------------------------------------------------------------- definitions

    public StoredDefinition saveDraft(Principal p, DefinitionKind kind, String id, Map<String, Object> doc,
            Long expectedRevision) {
        requireAnywhere(p, Permission.DEFINITION_WRITE);
        checkId(id);
        if (doc == null) {
            throw ApiException.badRequest("Missing document");
        }
        Object docId = doc.get("id");
        if (docId != null && !id.equals(String.valueOf(docId))) {
            throw ApiException.badRequest("Document id '" + docId + "' does not match path id '" + id + "'");
        }
        checkSize(doc);
        Studio.check(doc);
        String projectId = Studio.projectId(doc);
        requireIn(p, Permission.DEFINITION_WRITE, projectId);
        // Moving a definition out of a project needs write access there too.
        StoredDefinition existing = store.getDraft(p.tenantId(), kind, id).or(() -> store.getLatest(p.tenantId(), kind, id)).orElse(null);
        if (existing != null) {
            String from = Studio.projectId(existing.document());
            if (!java.util.Objects.equals(from, projectId)) {
                requireIn(p, Permission.DEFINITION_WRITE, from);
            }
        }
        if (projectId != null && projects.get(p.tenantId(), projectId).isEmpty()) {
            throw ApiException.badRequest("Unknown project '" + projectId + "'");
        }
        Map<String, Object> copy = new LinkedHashMap<>(doc);
        copy.put("id", id);
        return store.putDraft(p.tenantId(), kind, id, copy, expectedRevision, p.userId(), clock.instant());
    }

    public StoredDefinition getDraft(Principal p, DefinitionKind kind, String id) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        StoredDefinition d = store.getDraft(p.tenantId(), kind, id)
                .orElseThrow(() -> ApiException.notFound(kind + " draft '" + id + "'"));
        return visible(p, Permission.DEFINITION_READ, d, () -> ApiException.notFound(kind + " draft '" + id + "'"));
    }

    public StoredDefinition getVersion(Principal p, DefinitionKind kind, String id, Integer version) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        Optional<StoredDefinition> d = version == null ? store.getLatest(p.tenantId(), kind, id)
                : store.getVersion(p.tenantId(), kind, id, version);
        StoredDefinition found = d.orElseThrow(() -> ApiException.notFound(kind + " '" + id + "'" + (version == null ? "" : " v" + version)));
        return visible(p, Permission.DEFINITION_READ, found,
                () -> ApiException.notFound(kind + " '" + id + "'" + (version == null ? "" : " v" + version)));
    }

    public List<DefinitionSummary> list(Principal p, DefinitionKind kind) {
        return list(p, kind, null);
    }

    /** @param projectId only definitions of this project, or all when null */
    public List<DefinitionSummary> list(Principal p, DefinitionKind kind, String projectId) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        List<DefinitionSummary> all = store.list(p.tenantId(), kind);
        if (!p.can(Permission.DEFINITION_READ)) {
            all = all.stream().filter(d -> d.projectId() != null && p.can(Permission.DEFINITION_READ, d.projectId())).toList();
        }
        if (projectId == null) {
            return all;
        }
        return all.stream().filter(d -> projectId.equals(d.projectId())).toList();
    }

    public boolean deleteDraft(Principal p, DefinitionKind kind, String id) {
        requireAnywhere(p, Permission.DEFINITION_WRITE);
        StoredDefinition d = store.getDraft(p.tenantId(), kind, id).orElse(null);
        if (d != null) {
            visible(p, Permission.DEFINITION_WRITE, d, () -> ApiException.notFound(kind + " draft '" + id + "'"));
        }
        return store.deleteDraft(p.tenantId(), kind, id);
    }

    public ValidationReport validate(Principal p, DefinitionKind kind, Map<String, Object> doc) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        checkSize(doc);
        Studio.check(doc);
        return validateDoc(p.tenantId(), kind, doc);
    }

    /** Validates the current draft (including references) and freezes it as the next immutable version. */
    public StoredDefinition publish(Principal p, DefinitionKind kind, String id) {
        requireAnywhere(p, Permission.DEFINITION_WRITE);
        StoredDefinition draft = store.getDraft(p.tenantId(), kind, id)
                .orElseThrow(() -> ApiException.notFound(kind + " draft '" + id + "'"));
        visible(p, Permission.DEFINITION_WRITE, draft, () -> ApiException.notFound(kind + " draft '" + id + "'"));
        ValidationReport r = validateDoc(p.tenantId(), kind, draft.document());
        if (!r.valid()) {
            List<String> details = new ArrayList<>();
            for (ValidationReport.Item i : r.items()) {
                details.add(i.severity() + (i.elementId() == null ? "" : " [" + i.elementId() + "]") + ": " + i.message());
            }
            throw new ApiException(ApiException.Code.VALIDATION_FAILED, "Cannot publish: definition is invalid", details);
        }
        return store.publish(p.tenantId(), kind, id, p.userId(), clock.instant());
    }

    private ValidationReport validateDoc(String tenant, DefinitionKind kind, Map<String, Object> fullDoc) {
        Map<String, Object> doc = Studio.executable(fullDoc);
        List<ValidationReport.Item> items = new ArrayList<>();
        try {
            switch (kind) {
                case PROCESS -> {
                    ProcessModel m = ProcessMaps.fromMap(doc);
                    for (Issue i : m.validate()) {
                        items.add(new ValidationReport.Item(i.severity().name(), i.elementId(), i.message()));
                    }
                    for (var e : m.elements().values()) {
                        if (!profile.allows(e.type())) {
                            items.add(new ValidationReport.Item("ERROR", e.id(), e.type() + " is not available in the '" + profile + "' profile"));
                        }
                        for (String problem : NodeCatalog.validateConfig(e.type(), e.config())) {
                            items.add(new ValidationReport.Item("ERROR", e.id(), "config " + problem));
                        }
                    }
                    if (items.stream().noneMatch(i -> i.severity().equals("ERROR"))) {
                        ProcessCompiler.compile(m);
                        items.addAll(referenceItems(tenant, m));
                    }
                }
                case DECISION -> DmnLoader.load(doc);
                case AGENT -> AgentSpecLoader.fromMap(doc);
                case PIPELINE -> EipLoader.build(doc, tech.kayys.wayang.eip.Services.scratch((n, c, i) -> {
                    throw new tech.kayys.wayang.eip.Invoker.Permanent("validation only");
                }));
            }
        } catch (ProcessCompiler.InvalidProcessException e) {
            for (Issue i : e.issues()) {
                items.add(new ValidationReport.Item(i.severity().name(), i.elementId(), i.message()));
            }
        } catch (RuntimeException e) {
            items.add(new ValidationReport.Item("ERROR", null, String.valueOf(e.getMessage())));
        }
        return ValidationReport.of(items);
    }

    /** Referenced decisions must be published; referenced agents too, when named by id. */
    private List<ValidationReport.Item> referenceItems(String tenant, ProcessModel m) {
        List<ValidationReport.Item> out = new ArrayList<>();
        for (var e : m.elements().values()) {
            Object cfg;
            if (e.type() == ElementType.BUSINESS_RULE_TASK) {
                cfg = e.config().get("model");
                if (cfg instanceof String s && store.getLatest(tenant, DefinitionKind.DECISION, s).isEmpty()) {
                    out.add(new ValidationReport.Item("ERROR", e.id(), "Decision '" + s + "' is not published"));
                }
            } else if (e.type() == ElementType.AGENT_TASK) {
                cfg = e.config().get("agent");
                if (cfg instanceof String s && store.getLatest(tenant, DefinitionKind.AGENT, s).isEmpty()) {
                    out.add(new ValidationReport.Item("ERROR", e.id(), "Agent '" + s + "' is not published"));
                }
            } else if (e.type() == ElementType.EIP_TASK) {
                // a collect that sweeps to a process starts it later: it has to exist (published) by then
                for (String target : tech.kayys.wayang.eip.CollectSweeper.sweepTargets(e.config())) {
                    if (!target.equals(m.id()) && store.getLatest(tenant, DefinitionKind.PROCESS, target).isEmpty()) {
                        out.add(new ValidationReport.Item("ERROR", e.id(), "Process '" + target + "' is not published"));
                    }
                }
            }
        }
        return out;
    }

    // ---------------------------------------------------------------- projects

    public Project createProject(Principal p, String name, String description) {
        require(p, Permission.DEFINITION_WRITE);
        checkName(name);
        return projects.create(p.tenantId(), java.util.UUID.randomUUID().toString(), name.trim(), trimDescription(description),
                p.userId(), clock.instant());
    }

    public Project getProject(Principal p, String id) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        if (!p.can(Permission.DEFINITION_READ, id)) {
            throw ApiException.notFound("Project '" + id + "'");
        }
        return projects.get(p.tenantId(), id).orElseThrow(() -> ApiException.notFound("Project '" + id + "'"));
    }

    public List<Project> listProjects(Principal p) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        return projects.list(p.tenantId()).stream().filter(x -> p.can(Permission.DEFINITION_READ, x.id())).toList();
    }

    public Project updateProject(Principal p, String id, String name, String description) {
        requireAnywhere(p, Permission.DEFINITION_WRITE);
        if (!p.can(Permission.DEFINITION_WRITE, id)) {
            throw ApiException.notFound("Project '" + id + "'");
        }
        checkName(name);
        return projects.update(p.tenantId(), id, name.trim(), trimDescription(description), clock.instant())
                .orElseThrow(() -> ApiException.notFound("Project '" + id + "'"));
    }

    /** Deletes the project only when no definition still refers to it. */
    public void deleteProject(Principal p, String id) {
        require(p, Permission.DEFINITION_WRITE);
        if (projects.get(p.tenantId(), id).isEmpty()) {
            throw ApiException.notFound("Project '" + id + "'");
        }
        for (DefinitionKind k : DefinitionKind.values()) {
            if (store.list(p.tenantId(), k).stream().anyMatch(d -> id.equals(d.projectId()))) {
                throw new ApiException(ApiException.Code.CONFLICT, "Project '" + id + "' still contains " + k.name().toLowerCase()
                        + " definitions; move or delete them first");
            }
        }
        projects.delete(p.tenantId(), id);
    }

    private static void checkName(String name) {
        if (name == null || name.isBlank() || name.length() > 200) {
            throw ApiException.badRequest("Project name is required (max 200 characters)");
        }
    }

    private static String trimDescription(String d) {
        if (d != null && d.length() > 2000) {
            throw ApiException.badRequest("Description is longer than 2000 characters");
        }
        return d;
    }

    // -------------------------------------------------------------- BPMN XML

    /** Converts BPMN XML to the native document form for the Studio to edit; nothing is stored. */
    public Map<String, Object> importBpmn(Principal p, String xml) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        if (xml == null || xml.length() > MAX_DOCUMENT_CHARS) {
            throw ApiException.badRequest("BPMN XML missing or larger than " + MAX_DOCUMENT_CHARS + " characters");
        }
        try {
            ProcessModel m = BpmnXml.parse(xml);
            return Studio.withLayout(ProcessMaps.toMap(m), AutoLayout.layered(m));
        } catch (RuntimeException e) {
            throw ApiException.badRequest("Cannot import BPMN: " + e.getMessage());
        }
    }

    public String exportBpmn(Principal p, String id, Integer version) {
        StoredDefinition d = version == null && store.getLatest(p.tenantId(), DefinitionKind.PROCESS, id).isEmpty()
                ? getDraft(p, DefinitionKind.PROCESS, id)
                : getVersion(p, DefinitionKind.PROCESS, id, version);
        try {
            return BpmnXml.write(ProcessMaps.fromMap(Studio.executable(d.document())));
        } catch (tech.kayys.wayang.bpmn.xml.BpmnXmlException | IllegalArgumentException e) {
            throw new ApiException(ApiException.Code.VALIDATION_FAILED, "Cannot export to BPMN: " + e.getMessage());
        }
    }

    // ---------------------------------------------------------- authoring aids

    /** The Studio palette with config schemas. */
    public List<Map<String, Object>> nodeCatalog(Principal p) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        return NodeCatalog.entries().stream().filter(e -> profile.allows(tech.kayys.wayang.bpmn.ElementType.valueOf(e.type()))).map(NodeCatalog.Entry::toMap).toList();
    }

    public Object evalExpression(Principal p, String expression, Map<String, Object> variables) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        if (expression == null || expression.length() > 10_000) {
            throw ApiException.badRequest("Expression missing or too long");
        }
        try {
            return Expressions.evaluate(expression, variables == null ? Map.of() : variables);
        } catch (RuntimeException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    public Map<String, Object> testDecision(Principal p, Map<String, Object> doc, Map<String, Object> inputs) {
        requireAnywhere(p, Permission.DEFINITION_READ);
        checkSize(doc);
        try {
            return DmnLoader.load(doc).evaluate(inputs == null ? Map.of() : inputs);
        } catch (RuntimeException e) {
            throw ApiException.badRequest(e.getMessage());
        }
    }

    // -------------------------------------------------------------------- runs

    public RunView startRun(Principal p, String processId, Integer version, Map<String, Object> input) {
        requireAnywhere(p, Permission.RUN_START);
        StoredDefinition d = getVersionNoPerm(p, processId, version);
        if (!p.can(Permission.RUN_START, Studio.projectId(d.document()))) {
            throw ApiException.notFound("Published process '" + processId + "'" + (version == null ? "" : " v" + version));
        }
        ExecutionPlan plan;
        try {
            plan = ProcessCompiler.compile(ProcessMaps.fromMap(Studio.executable(d.document())));
        } catch (RuntimeException e) {
            throw new ApiException(ApiException.Code.VALIDATION_FAILED, "Stored process does not compile: " + e.getMessage());
        }
        return engine.start(p.tenantId(), p.userId(), processId, d.version(), plan, input == null ? Map.of() : input);
    }

    private StoredDefinition getVersionNoPerm(Principal p, String id, Integer version) {
        Optional<StoredDefinition> d = version == null ? store.getLatest(p.tenantId(), DefinitionKind.PROCESS, id)
                : store.getVersion(p.tenantId(), DefinitionKind.PROCESS, id, version);
        return d.orElseThrow(() -> ApiException.notFound("Published process '" + id + "'"
                + (version == null ? "" : " v" + version)));
    }

    /** The project a run belongs to: that of the process version it executes (immutable once published). */
    private String projectOfRun(String tenant, String processId, int version) {
        return store.getVersion(tenant, DefinitionKind.PROCESS, processId, version).map(d -> Studio.projectId(d.document())).orElse(null);
    }

    private boolean canOnRun(Principal p, Permission perm, RunView r) {
        return p.can(perm) || p.can(perm, projectOfRun(p.tenantId(), r.processId(), r.version()));
    }

    private boolean canOnRun(Principal p, Permission perm, String runId) {
        return engine.get(p.tenantId(), runId).map(r -> canOnRun(p, perm, r)).orElse(false);
    }

    public RunView getRun(Principal p, String runId) {
        requireAnywhere(p, Permission.RUN_READ);
        RunView r = engine.get(p.tenantId(), runId).orElseThrow(() -> ApiException.notFound("Run '" + runId + "'"));
        if (!canOnRun(p, Permission.RUN_READ, r)) {
            throw ApiException.notFound("Run '" + runId + "'");
        }
        return r;
    }

    public List<RunView> listRuns(Principal p, String processId, int limit) {
        requireAnywhere(p, Permission.RUN_READ);
        int max = Math.max(1, Math.min(limit, MAX_LIST));
        if (p.can(Permission.RUN_READ)) {
            return engine.list(p.tenantId(), processId, max);
        }
        // Project-scoped caller: over-read and keep only the runs of readable projects.
        List<RunView> out = new ArrayList<>();
        for (RunView r : engine.list(p.tenantId(), processId, MAX_LIST)) {
            if (out.size() >= max) {
                break;
            }
            if (canOnRun(p, Permission.RUN_READ, r)) {
                out.add(r);
            }
        }
        return out;
    }

    public void cancelRun(Principal p, String runId) {
        requireAnywhere(p, Permission.RUN_CANCEL);
        RunView r = engine.get(p.tenantId(), runId).orElseThrow(() -> ApiException.notFound("Run '" + runId + "'"));
        if (!canOnRun(p, Permission.RUN_CANCEL, r)) {
            throw ApiException.notFound("Run '" + runId + "'");
        }
        if (!engine.cancel(p.tenantId(), runId)) {
            throw new ApiException(ApiException.Code.CONFLICT, "Run '" + runId + "' cannot be cancelled in its current state");
        }
    }

    // ------------------------------------------------------------------- tasks

    /** Open human tasks of the caller's tenant (of readable projects, for project-scoped callers). */
    public List<TaskView> listTasks(Principal p, int limit) {
        requireAnywhere(p, Permission.RUN_READ);
        int max = Math.max(1, Math.min(limit, MAX_LIST));
        List<TaskView> out = new ArrayList<>();
        // The registry is not tenant-aware, so over-read and filter by run ownership.
        for (WaitRegistry.Registered r : waits.listByKind("human", MAX_LIST * 4)) {
            if (out.size() >= max) {
                break;
            }
            if (engine.get(p.tenantId(), r.runId()).isPresent() && canOnRun(p, Permission.RUN_READ, r.runId())) {
                out.add(ReferenceRunEngine.task(r.runId(), r.ticket()));
            }
        }
        return out;
    }

    public void completeTask(Principal p, String token, Map<String, Object> outputs) {
        requireAnywhere(p, Permission.TASK_COMPLETE);
        var reg = waits.find(token);
        if (reg.isEmpty() || !"human".equals(reg.get().ticket().kind())
                || engine.get(p.tenantId(), reg.get().runId()).isEmpty()
                || !canOnRun(p, Permission.TASK_COMPLETE, reg.get().runId())) {
            throw ApiException.notFound("Task");
        }
        if (!engine.completeWait(p.tenantId(), token, outputs == null ? Map.of() : outputs)) {
            throw new ApiException(ApiException.Code.CONFLICT, "Task was already completed");
        }
    }

    // ---------------------------------------------------------------- messages

    /**
     * Delivers a correlated message to a waiting run of the caller's tenant, or
     * buffers it. Known limitation: the buffer is not tenant-namespaced. A project-scoped caller may only
     * deliver to a run of its own project and cannot buffer (a buffered message is not tied to any project).
     */
    public String sendMessage(Principal p, String name, String correlationKey, Map<String, Object> payload) {
        requireAnywhere(p, Permission.MESSAGE_SEND);
        if (name == null || name.isBlank() || correlationKey == null) {
            throw ApiException.badRequest("name and correlationKey are required");
        }
        Map<String, Object> body = payload == null ? Map.of() : payload;
        var w = waits.findMessage(name, correlationKey);
        if (w.isPresent() && engine.get(p.tenantId(), w.get().runId()).isPresent()) {
            if (!canOnRun(p, Permission.MESSAGE_SEND, w.get().runId())) {
                throw ApiException.forbidden(Permission.MESSAGE_SEND);
            }
            if (engine.completeWait(p.tenantId(), w.get().ticket().token(), body)) {
                return "DELIVERED";
            }
        }
        require(p, Permission.MESSAGE_SEND);
        waits.bufferMessage(name, correlationKey, body);
        return "BUFFERED";
    }

    /** Fires due timers; normally called by a scheduler, exposed for admins and tests. */
    public int fireTimers(Principal p) {
        require(p, Permission.ADMIN);
        return engine.fireDueTimers();
    }

    // ------------------------------------------------------------------ events

    public AutoCloseable subscribe(Principal p, String runId, Consumer<RunEvent> listener) {
        requireAnywhere(p, Permission.RUN_READ);
        if (runId != null && (engine.get(p.tenantId(), runId).isEmpty() || !canOnRun(p, Permission.RUN_READ, runId))) {
            throw ApiException.notFound("Run '" + runId + "'");
        }
        if (p.can(Permission.RUN_READ) || runId != null) {
            return bus.subscribe(p.tenantId(), runId, listener);
        }
        // Project-scoped caller watching everything: deliver only events of runs in readable projects.
        Map<String, Boolean> allowed = new java.util.concurrent.ConcurrentHashMap<>();
        return bus.subscribe(p.tenantId(), null, e -> {
            if (e.runId() == null || allowed.computeIfAbsent(e.runId(), id -> canOnRun(p, Permission.RUN_READ, id))) {
                listener.accept(e);
            }
        });
    }

    // ----------------------------------------------------------------- helpers

    /** Held tenant-wide or on some project; callers then check or filter by project. */
    private static void requireAnywhere(Principal p, Permission perm) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.canAnywhere(perm)) {
            throw ApiException.forbidden(perm);
        }
    }

    /** The permission for a definition in {@code projectId} (null = not in a project: tenant-wide only). */
    private static void requireIn(Principal p, Permission perm, String projectId) {
        requireAnywhere(p, perm);
        if (!p.can(perm, projectId)) {
            throw ApiException.forbidden(perm);
        }
    }

    /** Hides what the caller may not see: a definition outside their projects looks like it does not exist. */
    private static StoredDefinition visible(Principal p, Permission perm, StoredDefinition d,
            java.util.function.Supplier<ApiException> notFound) {
        if (!p.can(perm, Studio.projectId(d.document()))) {
            throw notFound.get();
        }
        return d;
    }

    private static void require(Principal p, Permission perm) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(perm)) {
            throw ApiException.forbidden(perm);
        }
    }

    private static void checkId(String id) {
        if (id == null || !ID.matcher(id).matches()) {
            throw ApiException.badRequest("Invalid id; use 1-100 of letters, digits, '_', '.', '-'");
        }
    }

    private static void checkSize(Map<String, Object> doc) {
        if (doc == null) {
            throw ApiException.badRequest("Missing document");
        }
        if (Json.write(doc).length() > MAX_DOCUMENT_CHARS) {
            throw ApiException.badRequest("Document larger than " + MAX_DOCUMENT_CHARS + " characters");
        }
    }
}
