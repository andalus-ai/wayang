package tech.kayys.wayang.api.project;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.config.ConfigService;
import tech.kayys.wayang.api.config.ConfigStore;
import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.secret.InMemorySecretStore;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.run.RunEngine;
import tech.kayys.wayang.api.secret.SecretCipher;
import tech.kayys.wayang.api.secret.SecretService;
import tech.kayys.wayang.api.definition.Studio;
import tech.kayys.wayang.api.trigger.Trigger;
import tech.kayys.wayang.api.trigger.TriggerService;
import tech.kayys.wayang.api.trigger.TriggerStore;
import tech.kayys.wayang.api.WayangApi;

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

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.WaitRegistry;
import tech.kayys.wayang.runtime.json.Json;

class WorkspaceServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC);
    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final ProjectStore projects = new ProjectStore.InMemory();
    private final EventBus bus = new EventBus();
    private final RunEngine engine = new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, clock);
    private final WayangApi api = new WayangApi(store, projects, engine, waits, bus, clock);
    private final ConfigStore cfgStore = new ConfigStore.InMemory();
    private final ConfigService config = new ConfigService(cfgStore, x -> projects.get(x[0], x[1]).isPresent(), (t, p) -> null, clock);
    private final SecretService secrets = new SecretService(new InMemorySecretStore(),
            new SecretCipher(Map.of("k1", new byte[32]), "k1"), clock);
    private final Poller fake = (c, cursor) -> new Poller.Result(List.of(), "c");
    private final TriggerService triggers = new TriggerService(new TriggerStore.InMemory(), api, Map.of("fake", fake),
            (t, n) -> "x", clock);
    private final byte[] key = "0123456789abcdef0123456789abcdef".getBytes();

    private WorkspaceService ws() {
        return new WorkspaceService(api, triggers, config, secrets, null, clock);
    }

    private final Principal acme = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));

    private static Map<String, Object> decision() {
        return Map.of("id", "risk", "decisions", List.of(Map.of("name", "level", "type", "table", "hitPolicy", "FIRST",
                "inputs", List.of("amount"), "outputs", List.of("level"),
                "rules", List.of(Map.of("when", List.of(">= 1000"), "then", List.of("\"high\"")),
                        Map.of("when", List.of("-"), "then", List.of("\"low\""))))));
    }

    private static Map<String, Object> process(String id, Map<String, Object> serviceConfig) {
        return Map.of("id", id, "name", id, "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "rule", "type", "BUSINESS_RULE_TASK", "config", Map.of("model", "risk")),
                Map.of("id", "post", "type", "SERVICE_TASK", "config", serviceConfig),
                Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "rule"), Map.of("id", "f2", "source", "rule", "target", "post"),
                        Map.of("id", "f3", "source", "post", "target", "e")));
    }

    private static Map<String, Object> slack() {
        return Map.of("connector", "slack", "operation", "postMessage", "botToken", "${secret:SLACK}",
                "inputs", Map.of("channel", "'${config:CHANNEL}'", "text", "'hi'"));
    }

    /** A project with a decision, a process, a poll trigger and two settings, in tenant acme. */
    private String source() {
        String pid = api.createProject(acme, "Shop", "orders").id();
        api.saveDraft(acme, DefinitionKind.DECISION, "risk", withProject(decision(), pid), null);
        api.saveDraft(acme, DefinitionKind.PROCESS, "orders", withProject(process("orders", slack()), pid), null);
        api.publish(acme, DefinitionKind.DECISION, "risk");
        triggers.create(acme, "poll1", Trigger.Kind.POLL, "orders", Map.of("source", "fake", "everySeconds", 60));
        config.put(acme, pid, "CHANNEL", "#orders");
        config.put(acme, null, "REGION", "eu");
        return pid;
    }

    private static Map<String, Object> withProject(Map<String, Object> doc, String pid) {
        Map<String, Object> d = new LinkedHashMap<>(doc);
        d.put("studio", Map.of("projectId", pid, "layout", Map.of("s", Map.of("x", 1.0, "y", 2.0))));
        return d;
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> viaJson(Map<String, Object> b) {
        return (Map<String, Object>) Json.read(Json.write(b));
    }

    private WorkspaceService.Options apply(String onConflict, String target) {
        return new WorkspaceService.Options(false, onConflict, target, false, true, true, false, false);
    }

    private Principal other() {
        return new Principal("globex", "gus", Set.of(Role.ADMIN));
    }

    /** A second server: its own stores, same classes. */
    private final class Env {
        final DefinitionStore store = new DefinitionStore.InMemory();
        final ProjectStore projects = new ProjectStore.InMemory();
        final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
        final EventBus bus = new EventBus();
        final WayangApi api = new WayangApi(store, projects, new ReferenceRunEngine(store, waits, Map.of(), (s, i) -> Map.of(), bus, clock), waits, bus, clock);
        final ConfigService config = new ConfigService(new ConfigStore.InMemory(), x -> projects.get(x[0], x[1]).isPresent(), (t, p) -> null, clock);
        final SecretService secrets = new SecretService(new InMemorySecretStore(), new SecretCipher(Map.of("k9", new byte[32]), "k9"), clock);
        final TriggerService triggers = new TriggerService(new TriggerStore.InMemory(), api, Map.of("fake", fake), (t, n) -> "x", clock);

        WorkspaceService ws(byte[] signingKey) {
            return new WorkspaceService(api, triggers, config, secrets, signingKey, clock);
        }
    }

    @Test
    void exportCarriesDefinitionsTriggersSettingsAndWhatItRequiresButNoSecretValuesAndNoProjectId() {
        String pid = source();
        secrets.put(acme, "SLACK", "xoxb-super-secret-value");
        Map<String, Object> b = ws().export(acme, pid, false);
        String json = Json.write(b);
        assertFalse(json.contains("xoxb-super-secret-value"));
        assertFalse(json.contains(pid), "the source project id does not travel");
        assertEquals("wayang.workspace", b.get("format"));
        assertEquals(2, ((List<?>) b.get("definitions")).size());
        assertEquals(1, ((List<?>) b.get("triggers")).size());
        @SuppressWarnings("unchecked")
        Map<String, Object> req = (Map<String, Object>) b.get("requires");
        assertEquals(List.of("SLACK"), req.get("secrets"));
        assertEquals(List.of("CHANNEL"), req.get("config"));
        @SuppressWarnings("unchecked")
        Map<String, Object> cfg = (Map<String, Object>) b.get("config");
        assertEquals(Map.of("CHANNEL", "#orders"), cfg.get("project"));
        assertNull(cfg.get("tenant"), "tenant settings only on request");
        assertTrue(Json.write(ws().export(acme, pid, true)).contains("REGION"));
        assertNotNull(((Map<?, ?>) b.get("integrity")).get("sha256"));
        assertNull(((Map<?, ?>) b.get("integrity")).get("hmac"));
    }

    @Test
    void exportIsDeterministicAndSurvivesAJsonRoundTrip() {
        String pid = source();
        Map<String, Object> a = ws().export(acme, pid, false);
        Map<String, Object> b = ws().export(acme, pid, false);
        assertEquals(WorkspaceBundle.canonical(a), WorkspaceBundle.canonical(b));
        assertEquals(WorkspaceBundle.sha256(a), WorkspaceBundle.sha256(viaJson(a)), "the digest survives write and parse");
    }

    @Test
    void importIntoAnotherServerDryRunWritesNothingAndSaysWhatIsMissing() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        Env env = new Env();
        Principal p = other();
        var r = env.ws(null).importBundle(p, bundle, WorkspaceService.Options.defaults());
        assertTrue(r.dryRun() && r.ok(), r.problems().toString());
        assertEquals(List.of("SLACK"), r.missingSecrets());
        assertTrue(r.secretsChecked());
        assertEquals(List.of(), r.missingConfig(), "CHANNEL comes with the bundle");
        assertTrue(r.items().stream().anyMatch(i -> i.type().equals("project") && i.action().equals("create")));
        assertTrue(r.items().stream().anyMatch(i -> i.type().equals("trigger") && i.message().contains("disabled")));
        assertTrue(env.api.listProjects(p).isEmpty());
        assertTrue(env.api.list(p, DefinitionKind.PROCESS).isEmpty());
        assertTrue(env.triggers.list(p).isEmpty());
        assertTrue(r.webhookSecrets().isEmpty());
    }

    @Test
    void applyCreatesTheProjectDefinitionsSettingsAndADisabledTrigger() {
        String pid = source();
        Map<String, Object> bundle = viaJson(ws().export(acme, pid, false));
        Env env = new Env();
        Principal p = other();
        var r = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(false, "fail", null, false, true, true, false, false));
        assertTrue(r.ok(), r.problems().toString());
        assertFalse(r.dryRun());
        Project np = env.api.listProjects(p).get(0);
        assertEquals("Shop", np.name());
        assertNotEquals(pid, np.id());
        assertEquals(np.id(), Studio.projectId(env.api.getDraft(p, DefinitionKind.PROCESS, "orders").document()));
        assertEquals(np.id(), Studio.projectId(env.api.getDraft(p, DefinitionKind.DECISION, "risk").document()));
        assertNotNull(Studio.of(env.api.getDraft(p, DefinitionKind.PROCESS, "orders").document()).get("layout"), "other studio metadata is kept");
        assertEquals("#orders", env.config.list(p, np.id()).get(0).value());
        assertTrue(env.config.list(p, null).isEmpty(), "tenant settings were not asked for");
        Trigger t = env.triggers.list(p).get(0);
        assertEquals("poll1", t.id());
        assertFalse(t.enabled(), "importing never starts anything");
        assertThrows(ApiException.class, () -> env.api.getVersion(p, DefinitionKind.PROCESS, "orders", null), "nothing was published");
        assertTrue(api.listProjects(acme).size() == 1 && triggers.list(acme).get(0).enabled(), "the source is untouched");
    }

    @Test
    void aBundleCannotPlaceDefinitionsInAProjectItNames() {
        Env env = new Env();
        Principal p = other();
        String victim = env.api.createProject(p, "Victim", "").id();
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> doc = (Map<String, Object>) ((Map<String, Object>) ((List<Object>) bundle.get("definitions")).get(0)).get("document");
        @SuppressWarnings("unchecked")
        Map<String, Object> studio = (Map<String, Object>) doc.computeIfAbsent("studio", k -> new LinkedHashMap<String, Object>());
        studio.put("projectId", victim);
        bundle.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(bundle)));
        var r = env.ws(null).importBundle(p, bundle, apply("fail", null));
        assertTrue(r.ok(), r.problems().toString());
        assertTrue(env.api.list(p, DefinitionKind.PROCESS, victim).isEmpty() && env.api.list(p, DefinitionKind.DECISION, victim).isEmpty(),
                "nothing landed in the project the bundle named");
        assertEquals(1, env.api.list(p, DefinitionKind.PROCESS, r.project()).size());
    }

    @Test
    void publishOptionPublishesDecisionsBeforeTheProcessesThatUseThem() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        Env env = new Env();
        Principal p = other();
        var dry = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(true, "fail", null, true, true, true, false, false));
        assertTrue(dry.ok(), "a decision that is in the bundle is not reported as missing: " + dry.problems());
        assertTrue(dry.warnings().stream().noneMatch(w -> w.contains("Decision 'risk'")), dry.warnings().toString());
        var r = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(false, "fail", null, true, true, true, false, false));
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(1, env.api.getVersion(p, DefinitionKind.PROCESS, "orders", null).version());
        List<String> order = r.items().stream().filter(i -> i.action().equals("publish")).map(WorkspaceService.Item::id).toList();
        assertEquals(List.of("risk", "orders"), order);
    }

    @Test
    void aProcessThatNeedsADecisionNotInTheBundleIsOnlyAWarning() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        List<Object> defs = new ArrayList<>((List<?>) bundle.get("definitions"));
        defs.removeIf(d -> "DECISION".equals(((Map<?, ?>) d).get("kind")));
        bundle.put("definitions", defs);
        bundle.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(bundle)));
        var r = new Env().ws(null).importBundle(other(), bundle, WorkspaceService.Options.defaults());
        assertTrue(r.ok(), r.problems().toString());
        assertTrue(r.warnings().stream().anyMatch(w -> w.contains("Decision 'risk'")));
    }

    @Test
    void aChangedBundleIsRefused() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> proj = (Map<String, Object>) bundle.get("project");
        proj.put("name", "Renamed");
        var r = new Env().ws(null).importBundle(other(), bundle, WorkspaceService.Options.defaults());
        assertFalse(r.ok());
        assertTrue(r.problems().get(0).contains("Integrity"));
        bundle.remove("integrity");
        assertFalse(new Env().ws(null).importBundle(other(), bundle, WorkspaceService.Options.defaults()).ok());
    }

    @Test
    void tooManyDefinitionsOrTriggersAreRefused() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        Map<String, Object> many = new LinkedHashMap<>(bundle);
        List<Object> defs = new ArrayList<>();
        for (int i = 0; i <= WorkspaceService.MAX_DEFINITIONS; i++) {
            defs.add(Map.of("kind", "DECISION", "id", "d" + i, "document", decision()));
        }
        many.put("definitions", defs);
        many.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(many)));
        assertThrows(ApiException.class, () -> new Env().ws(null).importBundle(other(), many, WorkspaceService.Options.defaults()));
        Map<String, Object> hooks = new LinkedHashMap<>(bundle);
        List<Object> ts = new ArrayList<>();
        for (int i = 0; i <= WorkspaceService.MAX_TRIGGERS; i++) {
            ts.add(Map.of("id", "t" + i, "kind", "WEBHOOK", "processId", "orders", "config", Map.of()));
        }
        hooks.put("triggers", ts);
        hooks.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(hooks)));
        assertThrows(ApiException.class, () -> new Env().ws(null).importBundle(other(), hooks, WorkspaceService.Options.defaults()));
    }

    @Test
    void wrongFormatAndVersionAreRefused() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        Map<String, Object> notOurs = new LinkedHashMap<>(bundle);
        notOurs.put("format", "something.else");
        assertTrue(new Env().ws(null).importBundle(other(), notOurs, WorkspaceService.Options.defaults()).problems().get(0).contains("Not a Wayang"));
        Map<String, Object> future = new LinkedHashMap<>(bundle);
        future.put("formatVersion", 2);
        assertTrue(new Env().ws(null).importBundle(other(), future, WorkspaceService.Options.defaults()).problems().get(0).contains("Unsupported"));
        Map<String, Object> frac = new LinkedHashMap<>(bundle);
        frac.put("formatVersion", 1.5);
        frac.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(frac)));
        assertFalse(new Env().ws(null).importBundle(other(), frac, WorkspaceService.Options.defaults()).ok());
        assertThrows(ApiException.class, () -> ws().importBundle(acme, null, WorkspaceService.Options.defaults()));
    }

    @Test
    void aSigningKeyMakesTheImportingServerRequireTheSignature() {
        String pid = source();
        Map<String, Object> signed = viaJson(new WorkspaceService(api, triggers, config, secrets, key, clock).export(acme, pid, false));
        assertNotNull(((Map<?, ?>) signed.get("integrity")).get("hmac"));
        assertTrue(new Env().ws(key).importBundle(other(), signed, WorkspaceService.Options.defaults()).ok());
        var wrongKey = new Env().ws("another-key-another-key-another-k".getBytes()).importBundle(other(), signed, WorkspaceService.Options.defaults());
        assertFalse(wrongKey.ok());
        assertTrue(wrongKey.problems().get(0).contains("Signature"));
        Map<String, Object> unsigned = viaJson(ws().export(acme, pid, false));
        var rejected = new Env().ws(key).importBundle(other(), unsigned, WorkspaceService.Options.defaults());
        assertFalse(rejected.ok(), "a bundle anyone could have made is not accepted by a server that signs");
        assertTrue(new Env().ws(key).importBundle(other(), unsigned,
                new WorkspaceService.Options(true, "fail", null, false, true, true, false, true)).ok(), "unless the caller says so");
        // the signature covers the content: a recomputed digest alone is not enough
        Map<String, Object> forged = viaJson(signed);
        ((Map<String, Object>) forged.get("project")).put("name", "Forged");
        ((Map<String, Object>) forged.get("integrity")).put("sha256", WorkspaceBundle.sha256(forged));
        assertFalse(new Env().ws(key).importBundle(other(), forged, WorkspaceService.Options.defaults()).ok());
    }

    @Test
    void credentialsWrittenOutInFullAreNotExportedAndNotImported() {
        String pid = api.createProject(acme, "Leaky", "").id();
        Map<String, Object> bad = new LinkedHashMap<>(slack());
        bad.put("botToken", "xoxb-1234");
        api.saveDraft(acme, DefinitionKind.PROCESS, "leaky", withProject(process("leaky", bad), pid), null);
        var e = assertThrows(ApiException.class, () -> ws().export(acme, pid, false));
        assertEquals(ApiException.Code.VALIDATION_FAILED, e.code());
        String shown = String.valueOf(e.details());
        assertTrue(shown.contains("botToken"), shown);
        assertFalse(shown.contains("xoxb-1234"), "the value is never echoed");
        // a hand-made bundle with a literal token (digest recomputed) is refused too
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> procEntry = (Map<String, Object>) ((List<Object>) bundle.get("definitions")).stream()
                .filter(d -> "PROCESS".equals(((Map<?, ?>) d).get("kind"))).findFirst().orElseThrow();
        String s = Json.write(procEntry.get("document")).replace("${secret:SLACK}", "xoxb-forged");
        assertTrue(s.contains("xoxb-forged"));
        procEntry.put("document", Json.read(s));
        bundle.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(bundle)));
        var r = new Env().ws(null).importBundle(other(), bundle, WorkspaceService.Options.defaults());
        assertFalse(r.ok());
        assertTrue(r.problems().stream().anyMatch(x -> x.contains("botToken")), r.problems().toString());
        assertFalse(Json.write(r.problems()).contains("xoxb-forged"));
    }

    @Test
    void conflictsFailByDefaultSkipOrOverwriteInsideTheTargetProjectOnly() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        Env env = new Env();
        Principal p = other();
        assertTrue(env.ws(null).importBundle(p, bundle, apply("fail", null)).ok());
        String target = env.api.listProjects(p).get(0).id();
        // the same bundle again into a new project: the ids already exist elsewhere
        var again = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(false, "overwrite", null, false, false, true, false, false));
        assertFalse(again.ok(), "overwrite never reaches definitions of another project");
        assertTrue(again.problems().stream().anyMatch(x -> x.contains("outside the target project")), again.problems().toString());
        assertEquals(1, env.api.listProjects(p).size(), "nothing was created on a refused import");
        // into the same project
        assertFalse(env.ws(null).importBundle(p, bundle, apply("fail", target)).ok());
        var skip = env.ws(null).importBundle(p, bundle, apply("skip", target));
        assertTrue(skip.ok(), skip.problems().toString());
        assertTrue(skip.items().stream().anyMatch(i -> i.action().equals("skip") && i.id().equals("orders")));
        // change the local draft, then overwrite brings the bundle's back
        Map<String, Object> local = new LinkedHashMap<>(env.api.getDraft(p, DefinitionKind.PROCESS, "orders").document());
        local.put("name", "Edited here");
        env.api.saveDraft(p, DefinitionKind.PROCESS, "orders", local, env.api.getDraft(p, DefinitionKind.PROCESS, "orders").revision());
        var over = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(false, "overwrite", target, false, false, true, false, false));
        assertTrue(over.ok(), over.problems().toString());
        assertEquals("orders", env.api.getDraft(p, DefinitionKind.PROCESS, "orders").document().get("name"));
        assertEquals(3, env.api.getDraft(p, DefinitionKind.PROCESS, "orders").revision());
        // the same bundle over what is now identical: nothing is written, and a dry run says so
        var dry = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(true, "overwrite", target, false, false, true, false, false));
        assertTrue(dry.items().stream().anyMatch(i -> i.id().equals("orders") && i.action().equals("unchanged") && i.status().equals("skipped")), dry.items().toString());
        var same = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(false, "overwrite", target, false, false, true, false, false));
        assertTrue(same.ok(), same.problems().toString());
        assertEquals(3, env.api.getDraft(p, DefinitionKind.PROCESS, "orders").revision(), "an identical overwrite creates no revision");
        // with publish asked for, an identical draft is still written and published
        var pub = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(false, "overwrite", target, true, false, true, false, false));
        assertTrue(pub.items().stream().noneMatch(i -> i.action().equals("unchanged")), pub.items().toString());
    }

    @Test
    void anExistingTriggerIsNeverOverwritten() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        Env env = new Env();
        Principal p = other();
        assertTrue(env.ws(null).importBundle(p, bundle, apply("fail", null)).ok());
        String target = env.api.listProjects(p).get(0).id();
        env.api.deleteDraft(p, DefinitionKind.PROCESS, "orders");
        env.api.deleteDraft(p, DefinitionKind.DECISION, "risk");
        var r = env.ws(null).importBundle(p, bundle, apply("overwrite", target));
        assertFalse(r.ok());
        assertTrue(r.problems().stream().anyMatch(x -> x.contains("trigger 'poll1' already exists")), r.problems().toString());
        assertTrue(env.ws(null).importBundle(p, bundle, apply("skip", target)).ok());
    }

    @Test
    @SuppressWarnings("unchecked")
    void settingsAreReportedAsNewDifferentOrSameAndTheSameOnesAreNotWritten() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        Env env = new Env();
        Principal p = other();
        var first = env.ws(null).importBundle(p, bundle, apply("fail", null));
        assertTrue(first.ok(), first.problems().toString());
        assertTrue(first.items().stream().anyMatch(i -> i.type().equals("config") && i.action().equals("set") && i.status().equals("done")), "a new setting is set");
        String target = env.api.listProjects(p).get(0).id();
        var dry = new WorkspaceService.Options(true, "overwrite", target, false, false, true, false, false);
        var same = env.ws(null).importBundle(p, bundle, dry);
        assertTrue(same.items().stream().anyMatch(i -> i.type().equals("config") && i.id().equals("CHANNEL") && i.action().equals("unchanged")), same.items().toString());
        Map<String, Object> proj = (Map<String, Object>) ((Map<String, Object>) bundle.get("config")).get("project");
        proj.put("CHANNEL", "#elsewhere");
        bundle.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(bundle)));
        var diff = env.ws(null).importBundle(p, bundle, dry);
        var item = diff.items().stream().filter(i -> i.type().equals("config") && i.id().equals("CHANNEL")).findFirst().orElseThrow();
        assertEquals("overwrite", item.action());
        assertTrue(!item.message().contains("#elsewhere") && !item.message().contains("#orders"), "values are not put in the report");
        var applied = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(false, "overwrite", target, false, false, true, false, false));
        assertTrue(applied.ok(), applied.problems().toString());
        assertEquals("#elsewhere", env.config.list(p, target).stream().filter(c -> c.key().equals("CHANNEL")).findFirst().orElseThrow().value());
    }

    @Test
    void anImportThatFailsHalfwayStopsAndSaysWhatIsAlreadyInPlace() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> t = (Map<String, Object>) ((List<Object>) bundle.get("triggers")).get(0);
        ((Map<String, Object>) t.get("config")).put("source", "no-such-poller");
        bundle.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(bundle)));
        Env env = new Env();
        Principal p = other();
        assertTrue(env.ws(null).importBundle(p, bundle, WorkspaceService.Options.defaults()).ok(), "the dry run cannot know the poller");
        var r = env.ws(null).importBundle(p, bundle, apply("fail", null));
        assertFalse(r.ok());
        assertTrue(r.problems().get(r.problems().size() - 1).startsWith("Stopped after a failure"));
        assertTrue(r.items().stream().anyMatch(i -> i.type().equals("process") && i.status().equals("done")));
        assertEquals("error", r.items().get(r.items().size() - 1).status());
        assertEquals(1, env.api.list(p, DefinitionKind.PROCESS).size(), "what came before stays");
    }

    @Test
    void webhookTriggersGetANewSecretShownOnceAndStayDisabled() {
        String pid = source();
        triggers.create(acme, "hook1", Trigger.Kind.WEBHOOK, "orders", Map.of());
        Map<String, Object> bundle = viaJson(ws().export(acme, pid, false));
        assertFalse(Json.write(bundle).contains("secretHash"));
        Env env = new Env();
        var r = env.ws(null).importBundle(other(), bundle, apply("fail", null));
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(Set.of("hook1"), r.webhookSecrets().keySet());
        assertTrue(r.webhookSecrets().get("hook1").length() >= 40);
        assertTrue(env.triggers.list(other()).stream().noneMatch(Trigger::enabled));
    }

    @Test
    void permissionsAreTheCallersOwn() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        Env env = new Env();
        Principal v = new Principal("globex", "vic", Set.of(Role.VIEWER));
        assertEquals(ApiException.Code.FORBIDDEN, assertThrows(ApiException.class,
                () -> env.ws(null).importBundle(v, bundle, WorkspaceService.Options.defaults())).code());
        // a project-only editor can import into their project but not create triggers or tenant settings
        Principal admin = other();
        String target = env.api.createProject(admin, "T", "").id();
        Principal dev = new Principal("globex", "dev", Set.of(), Set.of(), Map.of(target, Set.of(Permission.DEFINITION_READ, Permission.DEFINITION_WRITE)));
        var r = env.ws(null).importBundle(dev, bundle, new WorkspaceService.Options(true, "fail", target, false, true, true, false, false));
        assertFalse(r.ok());
        assertTrue(r.problems().stream().anyMatch(x -> x.startsWith("triggers:")), r.problems().toString());
        var noTriggers = env.ws(null).importBundle(dev, bundle, new WorkspaceService.Options(false, "fail", target, false, false, true, false, false));
        assertTrue(noTriggers.ok(), noTriggers.problems().toString());
        assertEquals(1, env.api.list(dev, DefinitionKind.PROCESS, target).size());
        assertEquals(ApiException.Code.NOT_FOUND, assertThrows(ApiException.class,
                () -> env.ws(null).importBundle(dev, bundle, new WorkspaceService.Options(true, "fail", "nope", false, false, false, false, false))).code());
        // exporting: a project-only reader gets the definitions but no triggers, and is told so
        Map<String, Object> exp = env.ws(null).export(dev, target, false);
        assertEquals(List.of(), exp.get("triggers"));
        assertNotNull(exp.get("notes"));
        assertThrows(ApiException.class, () -> env.ws(null).export(new Principal("globex", "x", Set.of(Role.VIEWER), Set.of(), Map.of()), "nope", false));
        assertThrows(ApiException.class, () -> ws().export(viewer, "nope", false));
    }

    @Test
    void anotherTenantsProjectCannotBeExported() {
        String pid = source();
        assertThrows(ApiException.class, () -> ws().export(other(), pid, false));
    }

    @Test
    void settingsInABundleAreCheckedLikeAnyOther() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        @SuppressWarnings("unchecked")
        Map<String, Object> proj = (Map<String, Object>) ((Map<String, Object>) bundle.get("config")).get("project");
        proj.put("API_TOKEN", "abc");
        proj.put("OK_KEY", "has ${secret:X} inside");
        bundle.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(bundle)));
        var r = new Env().ws(null).importBundle(other(), bundle, WorkspaceService.Options.defaults());
        assertFalse(r.ok());
        assertEquals(2L, r.problems().stream().filter(x -> x.startsWith("config.project.")).count(), r.problems().toString());
    }

    @Test
    void tenantSettingsOnlyComeInWhenAsked() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), true));
        Env env = new Env();
        Principal p = other();
        assertTrue(env.ws(null).importBundle(p, bundle, apply("fail", null)).ok());
        assertTrue(env.config.list(p, null).isEmpty());
        var r = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(false, "skip", env.api.listProjects(p).get(0).id(), false, false, false, true, false));
        assertTrue(r.ok(), r.problems().toString());
        assertEquals("eu", env.config.list(p, null).get(0).value());
    }

    @Test
    void missingSettingsAreReportedWhenTheBundleDoesNotBringThem() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        var r = new Env().ws(null).importBundle(other(), bundle, new WorkspaceService.Options(true, "fail", null, false, true, false, false, false));
        assertEquals(List.of("CHANNEL"), r.missingConfig(), "project settings were switched off");
    }

    @Test
    void canonicalJsonIsStableAndStrict() {
        assertEquals("{\"a\":1,\"b\":[true,null,\"x\\n\"]}", WorkspaceBundle.canonical(Map.of("b", List.of(true, "x\n").size() == 2 ? java.util.Arrays.asList(true, null, "x\n") : List.of(), "a", 1)));
        assertEquals(WorkspaceBundle.canonical(Map.of("n", 1)), WorkspaceBundle.canonical(Map.of("n", 1.0)), "1 and 1.0 are the same JSON number");
        assertEquals(WorkspaceBundle.canonical(Map.of("n", 2.5)), WorkspaceBundle.canonical(Map.of("n", new java.math.BigDecimal("2.50"))));
        assertEquals("{\"n\":0}", WorkspaceBundle.canonical(Map.of("n", -0.0)));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceBundle.canonical(Map.of("n", new java.math.BigDecimal("1E+900"))));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceBundle.canonical(Map.of("n", Double.NaN)));
        assertThrows(IllegalArgumentException.class, () -> WorkspaceBundle.canonical(Map.of("n", new Object())));
        assertEquals("\"\\u2028\"", WorkspaceBundle.canonical("\u2028"));
    }

    @Test
    void credentialScanFlagsLiteralsPrivateKeysAndUrlPasswordsButNotReferences() {
        assertTrue(WorkspaceBundle.literalCredentials("x", Map.of("password", "${secret:P}", "tokenLimit", 5, "note", "plain")).isEmpty());
        assertEquals(List.of("x.apiKey"), WorkspaceBundle.literalCredentials("x", Map.of("apiKey", "abc")));
        assertEquals(List.of("x.headers.Authorization"), WorkspaceBundle.literalCredentials("x", Map.of("headers", Map.of("Authorization", "Bearer abc"))));
        assertTrue(WorkspaceBundle.literalCredentials("x", Map.of("headers", Map.of("Authorization", "Bearer ${secret:T}"))).isEmpty());
        assertEquals(1, WorkspaceBundle.literalCredentials("x", Map.of("url", "https://bob:hunter2@example.com/x")).size());
        assertEquals(1, WorkspaceBundle.literalCredentials("x", List.of("-----BEGIN RSA PRIVATE KEY-----\nabc")).size());
        assertTrue(WorkspaceBundle.literalCredentials("x", Map.of("url", "https://example.com/a@b")).isEmpty());
    }

    @Test
    void aProcessIsPublishedAfterTheProcessItsCollectSweepsTo() {
        Map<String, Object> bundle = viaJson(ws().export(acme, source(), false));
        Map<String, Object> gather = Map.of("id", "gather", "name", "Gather", "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "c", "type", "EIP_TASK", "config", Map.of("message", "x", "pipeline", Map.of("steps",
                        List.of(Map.of("type", "collect", "key", "'k'", "size", 3, "timeoutSeconds", 60, "sweepTo", "late"))))),
                Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "c"), Map.of("id", "f2", "source", "c", "target", "e")));
        Map<String, Object> late = Map.of("id", "late", "name", "Late", "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "t", "type", "USER_TASK", "config", Map.of("title", "Look")), Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "t"), Map.of("id", "f2", "source", "t", "target", "e")));
        List<Object> defs = new ArrayList<>((List<?>) bundle.get("definitions"));
        defs.add(Map.of("kind", "PROCESS", "id", "gather", "document", gather)); // named before the process it sweeps to
        defs.add(Map.of("kind", "PROCESS", "id", "late", "document", late));
        bundle.put("definitions", defs);
        bundle.put("integrity", Map.of("sha256", WorkspaceBundle.sha256(bundle)));
        Env env = new Env();
        Principal p = other();
        var r = env.ws(null).importBundle(p, bundle, new WorkspaceService.Options(false, "fail", null, true, true, true, false, false));
        assertTrue(r.ok(), r.problems().toString());
        List<String> order = r.items().stream().filter(i -> i.action().equals("publish")).map(WorkspaceService.Item::id).toList();
        assertTrue(order.indexOf("late") >= 0 && order.indexOf("late") < order.indexOf("gather"), order.toString());
    }
}
