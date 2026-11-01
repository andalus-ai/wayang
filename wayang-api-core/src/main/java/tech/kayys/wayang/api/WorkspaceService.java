package tech.kayys.wayang.api;

import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Export and import of a project as a {@link WorkspaceBundle}. Everything goes through the normal services, so every permission
 * check, size limit and id rule applies exactly as if the same calls were made one by one.
 *
 * <p>Import is a two-phase call: with {@code dryRun} (the default) nothing is written and the report lists what would happen,
 * every problem found, and what the target environment still lacks (secrets, settings). Applying is <b>not transactional</b>:
 * items are written in order (project, definitions, settings, triggers) and the first failure stops the run, leaving what came
 * before it in place; the report says which. Imported triggers are created <b>disabled</b>, so importing never starts anything.
 */
public final class WorkspaceService {

    public static final int MAX_DEFINITIONS = 500;
    public static final int MAX_TRIGGERS = 100;
    public static final int MAX_CONFIG = ConfigService.MAX_PER_SCOPE;
    public static final int MAX_CHARS = 8_000_000;

    private final WayangApi api;
    private final TriggerService triggers; // nullable
    private final ConfigService config; // nullable
    private final SecretService secrets; // nullable
    private final byte[] signingKey; // nullable
    private final Clock clock;

    public WorkspaceService(WayangApi api, TriggerService triggers, ConfigService config, SecretService secrets, byte[] signingKey,
            Clock clock) {
        this.api = api;
        this.triggers = triggers;
        this.config = config;
        this.secrets = secrets;
        this.signingKey = signingKey == null || signingKey.length == 0 ? null : signingKey.clone();
        this.clock = clock;
    }

    // ------------------------------------------------------------------ export

    /** @param includeTenantConfig also carry the tenant-wide settings (they are not part of the project) */
    public Map<String, Object> export(Principal p, String projectId, boolean includeTenantConfig) {
        return export(p, projectId, includeTenantConfig, false);
    }

    /**
     * @param releaseMode what a release is built from: only <em>published</em> versions (a draft is never used, and a definition with
     *                    no published version stops the export), no triggers, and only the settings the definitions refer to
     */
    public Map<String, Object> export(Principal p, String projectId, boolean includeTenantConfig, boolean releaseMode) {
        Project project = api.getProject(p, projectId);
        List<Object> defs = new ArrayList<>();
        Map<String, Object> processIds = new LinkedHashMap<>();
        List<String> unpublished = new ArrayList<>();
        List<String> newerDrafts = new ArrayList<>();
        for (DefinitionKind kind : DefinitionKind.values()) {
            for (DefinitionSummary s : api.list(p, kind, projectId)) {
                if (releaseMode && s.latestVersion() < 1) {
                    unpublished.add(kind.name().toLowerCase(java.util.Locale.ROOT) + " " + s.id());
                    continue;
                }
                StoredDefinition d = !releaseMode && s.hasDraft() ? api.getDraft(p, kind, s.id()) : api.getVersion(p, kind, s.id(), null);
                if (releaseMode && s.hasDraft() && !api.getDraft(p, kind, s.id()).document().equals(d.document())) {
                    newerDrafts.add(s.id()); // a draft identical to the published version is not a change
                }
                Map<String, Object> doc = portable(d.document());
                Map<String, Object> e = new LinkedHashMap<>();
                e.put("kind", kind.name());
                e.put("id", s.id());
                e.put("publishedVersion", s.latestVersion());
                e.put("document", doc);
                defs.add(e);
                if (kind == DefinitionKind.PROCESS) {
                    processIds.put(s.id(), true);
                }
            }
        }
        if (!unpublished.isEmpty()) {
            throw new ApiException(ApiException.Code.VALIDATION_FAILED,
                    "Not released: these definitions have no published version. Publish them first", unpublished);
        }
        if (defs.size() > MAX_DEFINITIONS) {
            throw ApiException.badRequest("A bundle holds at most " + MAX_DEFINITIONS + " definitions");
        }
        List<Object> trig = new ArrayList<>();
        boolean triggersSkipped = false;
        if (triggers != null) {
            if (p.can(Permission.DEFINITION_READ)) {
                for (Trigger t : triggers.list(p)) {
                    if (processIds.containsKey(t.processId()) && (!releaseMode || t.enabled())) { // a release runs only what is switched on
                        Map<String, Object> e = new LinkedHashMap<>();
                        e.put("id", t.id());
                        e.put("kind", t.kind().name());
                        e.put("processId", t.processId());
                        e.put("enabled", t.enabled());
                        e.put("config", new LinkedHashMap<>(t.config()));
                        trig.add(e);
                    }
                }
            } else {
                triggersSkipped = true; // triggers are tenant-wide: a project-only reader does not see them
            }
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        if (config != null) {
            Map<String, Object> proj = new LinkedHashMap<>();
            config.list(p, projectId).forEach(c -> proj.put(c.key(), c.value()));
            cfg.put("project", proj);
            if (includeTenantConfig) {
                Map<String, Object> ten = new LinkedHashMap<>();
                try {
                    config.list(p, null).forEach(c -> ten.put(c.key(), c.value()));
                    cfg.put("tenant", ten);
                } catch (ApiException e) {
                    if (!releaseMode) {
                        throw e; // an explicit request for tenant settings the caller may not read is an error; a release just goes without
                    }
                }
            }
        }
        Map<String, Object> bundle = new LinkedHashMap<>();
        bundle.put("format", WorkspaceBundle.FORMAT);
        bundle.put("formatVersion", WorkspaceBundle.VERSION);
        bundle.put("exportedAt", clock.instant().toString());
        Map<String, Object> pr = new LinkedHashMap<>();
        pr.put("name", project.name());
        pr.put("description", project.description() == null ? "" : project.description());
        bundle.put("project", pr);
        bundle.put("definitions", defs);
        bundle.put("triggers", trig);
        bundle.put("config", cfg);
        List<String> notes = new ArrayList<>();
        if (triggersSkipped) {
            notes.add("Triggers were not exported: they are tenant-wide and the caller can read definitions only in this project");
        }
        if (!newerDrafts.isEmpty()) {
            notes.add("These definitions have unpublished changes that are NOT in this release (the published version is used): " + newerDrafts);
        }
        if (!notes.isEmpty()) {
            bundle.put("notes", notes);
        }
        List<String> leaks = new ArrayList<>();
        leaks.addAll(WorkspaceBundle.literalCredentials("definitions", defs));
        leaks.addAll(WorkspaceBundle.literalCredentials("triggers", trig));
        if (!leaks.isEmpty()) {
            throw new ApiException(ApiException.Code.VALIDATION_FAILED,
                    "Not exported: these look like credentials written out in full. Store them as secrets and use ${secret:NAME}", leaks);
        }
        Map<String, Object> req = new LinkedHashMap<>();
        Set<String> sec = WorkspaceBundle.references("secret", List.of(defs, trig));
        req.put("secrets", sec.stream().filter(n -> !SecretService.reserved(n) && !VaultSecretResolver.isExternalName(n)).sorted().toList());
        req.put("externalSecrets", sec.stream().filter(VaultSecretResolver::isExternalName).sorted().toList());
        req.put("oauthConnections", sec.stream().filter(SecretService::reserved).sorted().toList());
        Set<String> cfgRefs = new LinkedHashSet<>(WorkspaceBundle.references("config", List.of(defs, trig)));
        req.put("config", cfgRefs.stream().sorted().toList());
        bundle.put("requires", req);
        if (releaseMode) { // a release carries only the settings its definitions refer to
            for (Object scope : cfg.values()) {
                if (scope instanceof Map<?, ?> m) {
                    m.keySet().removeIf(k -> !cfgRefs.contains(String.valueOf(k)));
                }
            }
        }
        Map<String, Object> integrity = new LinkedHashMap<>();
        integrity.put("sha256", WorkspaceBundle.sha256(bundle));
        if (signingKey != null) {
            integrity.put("hmac", WorkspaceBundle.hmac(bundle, signingKey));
        }
        bundle.put("integrity", integrity);
        return bundle;
    }

    /** The document without what binds it to this server's project: the id of the project it sits in. */
    @SuppressWarnings("unchecked")
    private static Map<String, Object> portable(Map<String, Object> doc) {
        Map<String, Object> d = new LinkedHashMap<>(doc);
        Object studio = d.get(Studio.KEY);
        if (studio instanceof Map<?, ?> m) {
            Map<String, Object> s = new LinkedHashMap<>((Map<String, Object>) m);
            s.remove("projectId");
            if (s.isEmpty()) {
                d.remove(Studio.KEY);
            } else {
                d.put(Studio.KEY, s);
            }
        }
        return d;
    }

    // ------------------------------------------------------------------ import

    public record Options(boolean dryRun, String onConflict, String targetProject, boolean publish, boolean triggers,
            boolean projectConfig, boolean tenantConfig, boolean allowUnsigned) {

        public Options {
            onConflict = onConflict == null ? "fail" : onConflict;
            if (!Set.of("fail", "skip", "overwrite").contains(onConflict)) {
                throw ApiException.badRequest("onConflict must be fail, skip or overwrite");
            }
        }

        /** Dry run, fail on conflicts, a new project, nothing published, triggers and project settings carried, tenant settings not. */
        public static Options defaults() {
            return new Options(true, "fail", null, false, true, true, false, false);
        }

        public static Options of(Map<String, Object> m) {
            if (m == null) {
                return defaults();
            }
            return new Options(!Boolean.FALSE.equals(m.get("dryRun")), (String) str(m, "onConflict"), (String) str(m, "targetProject"),
                    Boolean.TRUE.equals(m.get("publish")), !Boolean.FALSE.equals(m.get("triggers")), !Boolean.FALSE.equals(m.get("projectConfig")),
                    Boolean.TRUE.equals(m.get("tenantConfig")), Boolean.TRUE.equals(m.get("allowUnsigned")));
        }

        private static Object str(Map<String, Object> m, String k) {
            Object v = m.get(k);
            if (v != null && !(v instanceof String)) {
                throw ApiException.badRequest(k + " must be a string");
            }
            return v;
        }
    }

    public record Item(String type, String id, String action, String status, String message) {
    }

    public record Report(boolean dryRun, boolean ok, String project, List<Item> items, List<String> problems, List<String> warnings,
            List<String> missingSecrets, boolean secretsChecked, List<String> missingConfig, List<String> oauthConnections,
            Map<String, String> webhookSecrets) {
    }

    private static final List<DefinitionKind> ORDER = List.of(DefinitionKind.DECISION, DefinitionKind.AGENT, DefinitionKind.PIPELINE,
            DefinitionKind.PROCESS);

    private record Def(DefinitionKind kind, String id, Map<String, Object> doc) {
    }

    @SuppressWarnings("unchecked")
    public Report importBundle(Principal p, Map<String, Object> bundle, Options o) {
        List<Item> items = new ArrayList<>();
        List<String> problems = new ArrayList<>();
        List<String> warnings = new ArrayList<>();
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (bundle == null) {
            throw ApiException.badRequest("Missing bundle");
        }
        checkFormat(bundle, o, problems);
        if (!problems.isEmpty()) {
            return report(o, null, items, problems, warnings, List.of(), false, List.of(), List.of(), Map.of());
        }
        List<Def> defs = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        List<Object> rawDefs = list(bundle.get("definitions"), "definitions", MAX_DEFINITIONS);
        for (Object r : rawDefs) {
            if (!(r instanceof Map<?, ?> m) || !(m.get("document") instanceof Map<?, ?> doc) || !(m.get("kind") instanceof String k)
                    || !(m.get("id") instanceof String id)) {
                problems.add("definitions: each entry needs kind, id and document");
                continue;
            }
            DefinitionKind kind;
            try {
                kind = DefinitionKind.parse(k);
            } catch (ApiException e) {
                problems.add("definition '" + id + "': " + e.getMessage());
                continue;
            }
            if (!seen.add(kind + "/" + id)) {
                problems.add("definition " + kind + "/" + id + " appears twice");
                continue;
            }
            defs.add(new Def(kind, id, (Map<String, Object>) doc));
        }
        List<Object> rawTriggers = list(bundle.get("triggers"), "triggers", MAX_TRIGGERS);
        Map<String, Object> cfgBundle = bundle.get("config") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        Map<String, Object> projCfg = cfgBundle.get("project") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        Map<String, Object> tenCfg = cfgBundle.get("tenant") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
        if (projCfg.size() > MAX_CONFIG || tenCfg.size() > MAX_CONFIG) {
            problems.add("config: at most " + MAX_CONFIG + " settings per scope");
        }
        List<String> leaks = new ArrayList<>();
        leaks.addAll(WorkspaceBundle.literalCredentials("definitions", rawDefs));
        leaks.addAll(WorkspaceBundle.literalCredentials("triggers", rawTriggers));
        for (String l : leaks) {
            problems.add("looks like a credential written out in full: " + l);
        }
        if (!problems.isEmpty()) {
            return report(o, null, items, problems, warnings, List.of(), false, List.of(), List.of(), Map.of());
        }

        // ---- target project
        Project target = null;
        String projectLabel;
        Map<String, Object> pm = bundle.get("project") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        String newName = pm.get("name") instanceof String s && !s.isBlank() ? s : "Imported workspace";
        if (o.targetProject() != null) {
            target = api.getProject(p, o.targetProject());
            projectLabel = target.id();
            if (!p.can(Permission.DEFINITION_WRITE, target.id())) {
                throw ApiException.forbidden(Permission.DEFINITION_WRITE);
            }
            items.add(new Item("project", target.id(), "use", "ok", target.name()));
        } else {
            if (!p.can(Permission.DEFINITION_WRITE)) {
                throw ApiException.forbidden(Permission.DEFINITION_WRITE);
            }
            projectLabel = null;
            items.add(new Item("project", newName, "create", "ok", "A new project is created"));
        }
        String projectId = target == null ? null : target.id();

        // ---- definitions: conflicts and validation, nothing written
        Map<DefinitionKind, Map<String, DefinitionSummary>> existing = new LinkedHashMap<>();
        for (DefinitionKind k : DefinitionKind.values()) {
            Map<String, DefinitionSummary> m = new LinkedHashMap<>();
            api.list(p, k).forEach(s -> m.put(s.id(), s));
            existing.put(k, m);
        }
        Set<String> bundleRefs = new LinkedHashSet<>();
        defs.forEach(d -> bundleRefs.add(d.kind() + "/" + d.id()));
        Map<String, String> actions = new LinkedHashMap<>(); // kind/id -> create|overwrite|skip
        Map<String, Long> revisions = new LinkedHashMap<>();
        for (Def d : defs) {
            String key = d.kind() + "/" + d.id();
            DefinitionSummary ex = existing.get(d.kind()).get(d.id());
            String action = "create";
            if (ex != null) {
                if (projectId == null || !projectId.equals(ex.projectId())) {
                    items.add(new Item(d.kind().name().toLowerCase(), d.id(), "conflict", "error",
                            "Already exists" + (ex.projectId() == null ? " outside any project" : " in another project")
                                    + "; it is not touched even with overwrite"));
                    problems.add(key + " already exists outside the target project");
                    continue;
                }
                switch (o.onConflict()) {
                    case "skip" -> action = "skip";
                    case "overwrite" -> action = "overwrite";
                    default -> {
                        items.add(new Item(d.kind().name().toLowerCase(), d.id(), "conflict", "error", "Already exists in the project"));
                        problems.add(key + " already exists (use onConflict skip or overwrite)");
                        continue;
                    }
                }
            }
            Map<String, Object> doc = withProject(d.doc(), projectId);
            ValidationReport vr;
            try {
                vr = api.validate(p, d.kind(), doc);
            } catch (ApiException e) {
                items.add(new Item(d.kind().name().toLowerCase(), d.id(), action, "error", e.getMessage()));
                problems.add(key + ": " + e.getMessage());
                continue;
            }
            boolean bad = false;
            for (ValidationReport.Item i : vr.items()) {
                String m = i.message();
                if (i.severity().equals("ERROR") && isUnpublishedReference(m, bundleRefs)) {
                    continue; // satisfied by the bundle itself once it is published
                }
                if (i.severity().equals("ERROR") && isUnpublishedReference(m, null)) {
                    warnings.add(key + ": " + m + " (not in this bundle: publish it before this one)");
                } else if (i.severity().equals("ERROR")) {
                    bad = true;
                    problems.add(key + (i.elementId() == null ? "" : " [" + i.elementId() + "]") + ": " + m);
                } else {
                    warnings.add(key + (i.elementId() == null ? "" : " [" + i.elementId() + "]") + ": " + m);
                }
            }
            items.add(new Item(d.kind().name().toLowerCase(), d.id(), action, bad ? "error" : action.equals("skip") ? "skipped" : "ok",
                    bad ? "Invalid" : ""));
            actions.put(key, action);
            if (action.equals("overwrite")) {
                try {
                    revisions.put(key, api.getDraft(p, d.kind(), d.id()).revision());
                } catch (ApiException e) {
                    revisions.put(key, null); // only published versions exist: a new draft is created
                }
            }
        }

        // ---- settings
        Map<String, String> projectSettings = new LinkedHashMap<>();
        Map<String, String> tenantSettings = new LinkedHashMap<>();
        if (config != null) {
            if (o.projectConfig()) {
                collectSettings(projCfg, "config.project", projectSettings, problems);
            }
            if (o.tenantConfig()) {
                if (!p.can(Permission.DEFINITION_WRITE)) {
                    problems.add("config.tenant: tenant-wide settings need tenant-wide definition-write");
                } else {
                    collectSettings(tenCfg, "config.tenant", tenantSettings, problems);
                }
            }
        } else if (!projCfg.isEmpty() || !tenCfg.isEmpty()) {
            warnings.add("config: this server has no settings store; the bundle's settings are ignored");
        }
        projectSettings.keySet().forEach(k -> items.add(new Item("config", k, "set", "ok", "project")));
        tenantSettings.keySet().forEach(k -> items.add(new Item("config", k, "set", "ok", "tenant")));

        // ---- triggers
        List<Map<String, Object>> trigs = new ArrayList<>();
        if (!o.triggers()) {
            if (!rawTriggers.isEmpty()) {
                warnings.add(rawTriggers.size() + " trigger(s) in the bundle were not imported (triggers=false)");
            }
        } else if (triggers == null) {
            if (!rawTriggers.isEmpty()) {
                warnings.add("triggers: this server has no trigger service; the bundle's triggers are ignored");
            }
        } else if (!rawTriggers.isEmpty() && !(p.can(Permission.DEFINITION_WRITE) && p.can(Permission.RUN_START))) {
            problems.add("triggers: creating triggers needs tenant-wide definition-write and run-start");
        } else {
            Set<String> haveTriggers = new LinkedHashSet<>();
            if (!rawTriggers.isEmpty() && p.can(Permission.DEFINITION_READ)) {
                triggers.list(p).forEach(t -> haveTriggers.add(t.id()));
            }
            Set<String> ids = new LinkedHashSet<>();
            for (Object r : rawTriggers) {
                if (!(r instanceof Map<?, ?> m) || !(m.get("id") instanceof String id) || !(m.get("processId") instanceof String proc)
                        || !(m.get("kind") instanceof String kind)) {
                    problems.add("triggers: each entry needs id, kind and processId");
                    continue;
                }
                if (!TriggerService.ID.matcher(id).matches() || !ids.add(id)) {
                    problems.add("trigger '" + id + "': invalid or repeated id");
                    continue;
                }
                if (!kind.equals("WEBHOOK") && !kind.equals("POLL") && !kind.equals("SCHEDULE")) {
                    problems.add("trigger '" + id + "': kind must be WEBHOOK, POLL or SCHEDULE");
                    continue;
                }
                if (kind.equals("SCHEDULE")) {
                    try {
                        TriggerService.validateSchedule(m.get("config") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of());
                    } catch (ApiException e) {
                        problems.add("trigger '" + id + "': " + e.getMessage());
                        continue;
                    }
                }
                boolean processKnown = bundleRefs.contains("PROCESS/" + proc) || existing.get(DefinitionKind.PROCESS).containsKey(proc);
                if (!processKnown) {
                    problems.add("trigger '" + id + "': process '" + proc + "' is neither in the bundle nor on this server");
                    continue;
                }
                if (bundleRefs.contains("PROCESS/" + proc) && "skip".equals(actions.get("PROCESS/" + proc))) {
                    warnings.add("trigger '" + id + "': its process was skipped; it will point at the existing one");
                }
                if (haveTriggers.contains(id)) {
                    switch (o.onConflict()) {
                        case "skip" -> {
                            items.add(new Item("trigger", id, "skip", "skipped", "Already exists"));
                            continue;
                        }
                        default -> {
                            // a trigger holds a webhook secret hash and a cursor; replacing one silently would break callers
                            items.add(new Item("trigger", id, "conflict", "error", "Already exists; triggers are never overwritten"));
                            problems.add("trigger '" + id + "' already exists (use onConflict skip, or delete it first)");
                            continue;
                        }
                    }
                }
                items.add(new Item("trigger", id, "create", "ok", "Created disabled"));
                trigs.add((Map<String, Object>) m);
            }
        }

        // ---- what the environment still needs
        Set<String> needSecrets = new LinkedHashSet<>(WorkspaceBundle.references("secret", List.of(rawDefs, rawTriggers)));
        Set<String> needConfig = new LinkedHashSet<>(WorkspaceBundle.references("config", List.of(rawDefs, rawTriggers)));
        List<String> oauth = needSecrets.stream().filter(SecretService::reserved).sorted().toList();
        needSecrets.removeIf(SecretService::reserved);
        List<String> external = needSecrets.stream().filter(VaultSecretResolver::isExternalName).sorted().toList();
        needSecrets.removeIf(VaultSecretResolver::isExternalName);
        if (!external.isEmpty()) {
            warnings.add("These are read from Vault and must exist there for each tenant: " + String.join(", ", external));
        }
        List<String> missingSecrets = new ArrayList<>();
        boolean secretsChecked = false;
        if (secrets != null && p.can(Permission.SECRET_LIST)) {
            secretsChecked = true;
            Set<String> have = new LinkedHashSet<>();
            secrets.list(p).forEach(m -> have.add(m.name()));
            needSecrets.stream().filter(n -> !have.contains(n)).sorted().forEach(missingSecrets::add);
        } else {
            needSecrets.stream().sorted().forEach(missingSecrets::add);
        }
        List<String> missingConfig = new ArrayList<>();
        if (config != null) {
            Set<String> have = new LinkedHashSet<>(projectSettings.keySet());
            have.addAll(tenantSettings.keySet());
            try {
                config.list(p, null).forEach(c -> have.add(c.key()));
                if (projectId != null) {
                    config.list(p, projectId).forEach(c -> have.add(c.key()));
                }
            } catch (ApiException ignored) {
                // cannot read settings: report what the bundle itself does not bring
            }
            needConfig.stream().filter(n -> !have.contains(n)).sorted().forEach(missingConfig::add);
        } else {
            needConfig.stream().sorted().forEach(missingConfig::add);
        }
        if (!oauth.isEmpty()) {
            warnings.add("Connected accounts must be set up on this server: " + String.join(", ", oauth));
        }

        if (o.dryRun() || !problems.isEmpty()) {
            return new Report(o.dryRun(), problems.isEmpty(), projectLabel, items, problems, warnings, missingSecrets, secretsChecked,
                    missingConfig, oauth, Map.of());
        }

        // ---- apply
        Map<String, String> webhookSecrets = new LinkedHashMap<>();
        List<Item> done = new ArrayList<>();
        try {
            if (target == null) {
                target = api.createProject(p, newName, pm.get("description") instanceof String ds ? ds : "");
                projectId = target.id();
                projectLabel = projectId;
                done.add(new Item("project", projectId, "create", "done", newName));
            } else {
                done.add(new Item("project", projectId, "use", "done", target.name()));
            }
            List<Def> applied = new ArrayList<>();
            for (DefinitionKind k : ORDER) {
                for (Def d : defs) {
                    String key = d.kind() + "/" + d.id();
                    String action = actions.get(key);
                    if (d.kind() != k || action == null) {
                        continue;
                    }
                    if (action.equals("skip")) {
                        done.add(new Item(k.name().toLowerCase(), d.id(), "skip", "skipped", ""));
                        continue;
                    }
                    api.saveDraft(p, k, d.id(), withProject(d.doc(), projectId), action.equals("overwrite") ? revisions.get(key) : null);
                    done.add(new Item(k.name().toLowerCase(), d.id(), action, "done", ""));
                    applied.add(d);
                }
            }
            if (o.publish()) {
                for (DefinitionKind k : ORDER) {
                    for (Def d : k == DefinitionKind.PROCESS ? sweepTargetsFirst(applied) : applied) {
                        if (d.kind() == k) {
                            StoredDefinition v = api.publish(p, k, d.id());
                            done.add(new Item(k.name().toLowerCase(), d.id(), "publish", "done", "v" + v.version()));
                        }
                    }
                }
            }
            for (var e : projectSettings.entrySet()) {
                config.put(p, projectId, e.getKey(), e.getValue());
                done.add(new Item("config", e.getKey(), "set", "done", "project"));
            }
            for (var e : tenantSettings.entrySet()) {
                config.put(p, null, e.getKey(), e.getValue());
                done.add(new Item("config", e.getKey(), "set", "done", "tenant"));
            }
            for (Map<String, Object> t : trigs) {
                String id = (String) t.get("id");
                Map<String, Object> cfg = t.get("config") instanceof Map<?, ?> c ? (Map<String, Object>) c : Map.of();
                var created = triggers.create(p, id, Trigger.Kind.valueOf((String) t.get("kind")), (String) t.get("processId"), cfg);
                triggers.setEnabled(p, id, false);
                if (created.webhookSecret() != null) {
                    webhookSecrets.put(id, created.webhookSecret());
                }
                done.add(new Item("trigger", id, "create", "done", "disabled"));
            }
        } catch (ApiException e) {
            done.add(new Item("error", "", "stopped", "error", e.getMessage()));
            problems.add("Stopped after a failure: " + e.getMessage() + ". What was written before it is still in place (see items).");
            return new Report(false, false, projectLabel, done, problems, warnings, missingSecrets, secretsChecked, missingConfig, oauth,
                    webhookSecrets);
        }
        return new Report(false, true, projectLabel, done, problems, warnings, missingSecrets, secretsChecked, missingConfig, oauth,
                webhookSecrets);
    }

    private Report report(Options o, String project, List<Item> items, List<String> problems, List<String> warnings,
            List<String> missingSecrets, boolean checked, List<String> missingConfig, List<String> oauth, Map<String, String> hooks) {
        return new Report(o.dryRun(), false, project, items, problems, warnings, missingSecrets, checked, missingConfig, oauth, hooks);
    }

    private void checkFormat(Map<String, Object> bundle, Options o, List<String> problems) {
        if (!WorkspaceBundle.FORMAT.equals(bundle.get("format"))) {
            problems.add("Not a Wayang workspace bundle (format must be '" + WorkspaceBundle.FORMAT + "')");
            return;
        }
        if (!(bundle.get("formatVersion") instanceof Number n) || n.intValue() != WorkspaceBundle.VERSION || n.doubleValue() != n.intValue()) {
            problems.add("Unsupported bundle version " + bundle.get("formatVersion") + " (this server reads version " + WorkspaceBundle.VERSION + ")");
            return;
        }
        try {
            if (WorkspaceBundle.canonical(bundle).length() > MAX_CHARS) {
                problems.add("Bundle is larger than " + MAX_CHARS + " characters");
                return;
            }
            Object integ = bundle.get("integrity");
            Map<?, ?> im = integ instanceof Map<?, ?> m ? m : Map.of();
            if (!WorkspaceBundle.constantTimeEquals(WorkspaceBundle.sha256(bundle), im.get("sha256") instanceof String s ? s : null)) {
                problems.add("Integrity check failed: the bundle was changed after it was exported (or is truncated)");
                return;
            }
            if (signingKey != null && !o.allowUnsigned()) {
                String want = WorkspaceBundle.hmac(bundle, signingKey);
                if (!WorkspaceBundle.constantTimeEquals(want, im.get("hmac") instanceof String s ? s : null)) {
                    problems.add("Signature check failed: this server only accepts bundles exported by a server holding its signing key");
                }
            }
        } catch (IllegalArgumentException e) {
            problems.add("Bundle is not valid: " + e.getMessage());
        }
    }

    private static List<Object> list(Object v, String what, int max) {
        if (v == null) {
            return List.of();
        }
        if (!(v instanceof List<?> l)) {
            throw ApiException.badRequest(what + " must be a list");
        }
        if (l.size() > max) {
            throw ApiException.badRequest(what + ": at most " + max + " entries");
        }
        return new ArrayList<>(l);
    }

    private static void collectSettings(Map<String, Object> from, String path, Map<String, String> into, List<String> problems) {
        for (var e : from.entrySet()) {
            try {
                ConfigService.checkKey(e.getKey());
                if (!(e.getValue() instanceof String v)) {
                    throw ApiException.badRequest("value must be a string");
                }
                ConfigService.checkValue(v);
                into.put(e.getKey(), v);
            } catch (ApiException ex) {
                problems.add(path + "." + e.getKey() + ": " + ex.getMessage());
            }
        }
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> withProject(Map<String, Object> doc, String projectId) {
        Map<String, Object> d = new LinkedHashMap<>(doc);
        Map<String, Object> studio = d.get(Studio.KEY) instanceof Map<?, ?> m ? new LinkedHashMap<>((Map<String, Object>) m) : new LinkedHashMap<>();
        studio.remove("projectId"); // never trusted from the bundle
        if (projectId != null) {
            studio.put("projectId", projectId);
        }
        if (studio.isEmpty()) {
            d.remove(Studio.KEY);
        } else {
            d.put(Studio.KEY, studio);
        }
        return d;
    }

    /** "Decision 'x' is not published" / "Agent 'x' is not published": true when x (of that kind) is in {@code inBundle}, or any when null. */
    private static boolean isUnpublishedReference(String message, Set<String> inBundle) {
        if (message == null || !message.endsWith("' is not published")) {
            return false;
        }
        String kind = message.startsWith("Decision '") ? "DECISION" : message.startsWith("Agent '") ? "AGENT" : message.startsWith("Process '") ? "PROCESS" : null;
        if (kind == null) {
            return false;
        }
        if (inBundle == null) {
            return true;
        }
        String id = message.substring(message.indexOf('\'') + 1, message.length() - "' is not published".length());
        return inBundle.contains(kind + "/" + id);
    }

    /**
     * Processes in publish order: one that a collect sweeps to goes before the one that names it (a process may only be published
     * once what it starts later is). Others keep their order; a cycle is left as it is and fails at publish with a clear message.
     */
    private static List<Def> sweepTargetsFirst(List<Def> defs) {
        List<Def> procs = new ArrayList<>(defs.stream().filter(d -> d.kind() == DefinitionKind.PROCESS).toList());
        List<Def> out = new ArrayList<>(defs.stream().filter(d -> d.kind() != DefinitionKind.PROCESS).toList());
        Set<String> placed = new java.util.HashSet<>();
        boolean progress = true;
        while (!procs.isEmpty() && progress) {
            progress = false;
            for (var it = procs.iterator(); it.hasNext();) {
                Def d = it.next();
                Set<String> needs = new java.util.TreeSet<>(tech.kayys.wayang.eip.CollectSweeper.sweepTargets(d.doc()));
                needs.remove(d.id());
                needs.retainAll(procs.stream().map(Def::id).collect(java.util.stream.Collectors.toSet()));
                needs.removeAll(placed);
                if (needs.isEmpty()) {
                    out.add(d);
                    placed.add(d.id());
                    it.remove();
                    progress = true;
                }
            }
        }
        out.addAll(procs); // a cycle
        return out;
    }
}
