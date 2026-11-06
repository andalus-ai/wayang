package tech.kayys.wayang.api.project;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.config.ConfigService;
import tech.kayys.wayang.api.config.ConfigStore;
import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.secret.InMemorySecretStore;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.run.RunEngine;
import tech.kayys.wayang.api.secret.SecretCipher;
import tech.kayys.wayang.api.secret.SecretService;
import tech.kayys.wayang.api.trigger.Trigger;
import tech.kayys.wayang.api.trigger.TriggerService;
import tech.kayys.wayang.api.trigger.TriggerStore;
import tech.kayys.wayang.api.WayangApi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.WaitRegistry;

class ReleaseServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC);
    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final ProjectStore projects = new ProjectStore.InMemory();
    private final EventBus bus = new EventBus();
    private final RunEngine engine = new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, clock);
    private final WayangApi api = new WayangApi(store, projects, engine, waits, bus, clock);
    private final ConfigService config = new ConfigService(new ConfigStore.InMemory(), x -> projects.get(x[0], x[1]).isPresent(), (t, p) -> null, clock);
    private final SecretService secrets = new SecretService(new InMemorySecretStore(), new SecretCipher(Map.of("k1", new byte[32]), "k1"), clock);
    private final Poller fake = (c, cursor) -> new Poller.Result(List.of(), "c");
    private final TriggerService triggers = new TriggerService(new TriggerStore.InMemory(), api, Map.of("fake", fake, "rss", fake), (t, n) -> "x", clock);
    private final WorkspaceService ws = new WorkspaceService(api, triggers, config, secrets, null, clock);
    private final ReleaseService releases = new ReleaseService(ws, api, "0.0.1");

    private final Principal acme = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));
    private final Principal other = new Principal("globex", "gus", Set.of(Role.ADMIN));

    private static Map<String, Object> decision(String level) {
        return Map.of("id", "risk", "decisions", List.of(Map.of("name", "level", "type", "table", "hitPolicy", "FIRST",
                "inputs", List.of("amount"), "outputs", List.of("level"),
                "rules", List.of(Map.of("when", List.of(">= 1000"), "then", List.of("\"" + level + "\"")),
                        Map.of("when", List.of("-"), "then", List.of("\"low\""))))));
    }

    private static Map<String, Object> process(String id) {
        return Map.of("id", id, "name", id, "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "rule", "type", "BUSINESS_RULE_TASK", "config", Map.of("model", "risk")),
                Map.of("id", "post", "type", "SERVICE_TASK", "config", Map.of("connector", "slack", "operation", "postMessage",
                        "botToken", "${secret:SLACK}", "inputs", Map.of("channel", "'${config:CHANNEL}'", "text", "'hi'"))),
                Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "rule"), Map.of("id", "f2", "source", "rule", "target", "post"),
                        Map.of("id", "f3", "source", "post", "target", "e")));
    }

    private static Map<String, Object> inProject(Map<String, Object> doc, String pid) {
        Map<String, Object> d = new LinkedHashMap<>(doc);
        d.put("studio", Map.of("projectId", pid, "layout", Map.of("s", Map.of("x", 1.0, "y", 2.0))));
        return d;
    }

    private String project() {
        String pid = api.createProject(acme, "Shop Orders!", "orders").id();
        api.saveDraft(acme, DefinitionKind.DECISION, "risk", inProject(decision("high"), pid), null);
        api.saveDraft(acme, DefinitionKind.PROCESS, "orders", inProject(process("orders"), pid), null);
        api.publish(acme, DefinitionKind.DECISION, "risk");
        api.publish(acme, DefinitionKind.PROCESS, "orders");
        triggers.create(acme, "poll1", Trigger.Kind.POLL, "orders", Map.of("source", "rss", "url", "https://example.com/feed", "everySeconds", 60));
        triggers.create(acme, "hook1", Trigger.Kind.WEBHOOK, "orders", Map.of());
        triggers.create(acme, "off1", Trigger.Kind.POLL, "orders", Map.of("source", "rss", "url", "https://example.com/off"));
        triggers.setEnabled(acme, "off1", false);
        config.put(acme, pid, "CHANNEL", "#orders");
        config.put(acme, pid, "UNUSED", "nope");
        config.put(acme, null, "REGION", "eu");
        return pid;
    }

    private static Map<String, String> unzip(byte[] zip) throws Exception {
        Map<String, String> m = new LinkedHashMap<>();
        try (ZipInputStream z = new ZipInputStream(new ByteArrayInputStream(zip))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                m.put(e.getName(), new String(z.readAllBytes(), StandardCharsets.UTF_8));
            }
        }
        return m;
    }

    @Test
    void aReleaseIsBuiltFromPublishedVersionsAndCarriesOnlyWhatItNeeds() throws Exception {
        String pid = project();
        // a newer draft of the process exists: the release must use the published one and say so
        var changed = new LinkedHashMap<>(process("orders"));
        changed.put("name", "orders v2");
        api.saveDraft(acme, DefinitionKind.PROCESS, "orders", inProject(changed, pid), api.getDraft(acme, DefinitionKind.PROCESS, "orders").revision());
        var r = releases.release(acme, pid, Map.of("groupId", "com.shop"));
        assertEquals("shop-orders-1.0.0.zip", r.filename());
        var plan = r.plan();
        assertEquals(List.of("orders"), plan.processes());
        assertEquals(List.of("wayang.bpmn.control-flow", "wayang.dmn.decision", "wayang.service.connector"), plan.components());
        assertEquals(List.of("slack"), plan.connectors());
        assertEquals(List.of("SLACK", "trigger.hook1"), plan.requiredSecrets(), "the webhook's shared secret is supplied by the environment");
        assertEquals(List.of("CHANNEL"), plan.requiredConfig());
        assertEquals("SERVICE", plan.target());
        assertFalse(plan.bundleSha256().isEmpty());
        Map<String, String> files = unzip(r.zip());
        String bundle = files.get("src/main/resources/wayang/bundle.json");
        assertTrue(bundle.contains("#orders"), "the setting the workflow uses is carried");
        assertFalse(bundle.contains("nope") || bundle.contains("UNUSED"), "a setting nothing refers to is not");
        assertFalse(bundle.contains("REGION") || bundle.contains("\"eu\""), "nor tenant settings that nothing refers to");
        assertTrue(bundle.contains("poll1") && bundle.contains("hook1"), "enabled triggers are part of a release");
        assertFalse(bundle.contains("off1"), "a switched-off trigger is not");
        assertFalse(bundle.contains("secretHash"), "no hash of the design-time webhook secret");
        assertFalse(bundle.contains("projectId") || bundle.contains(pid), "nothing ties it to this server");
        assertTrue(bundle.contains("unpublished changes") && bundle.contains("orders"), "the newer draft is reported");
        assertTrue(files.get("pom.xml").contains("<groupId>com.shop</groupId>"));
        assertTrue(files.get("pom.xml").contains("wayang-connectors"));
        assertTrue(files.containsKey("src/main/java/com/example/shoporders/App.java"), files.keySet().toString());
        assertFalse(bundle.contains("xoxb"), "no secret value, only the reference");
        assertTrue(bundle.contains("${secret:SLACK}"));
    }

    @Test
    void aDraftIdenticalToThePublishedVersionIsNotReportedAsAChange() throws Exception {
        String pid = project();
        assertFalse(unzip(releases.release(acme, pid, null).zip()).get("src/main/resources/wayang/bundle.json").contains("unpublished changes"));
    }

    @Test
    void theReleaseUsesThePublishedVersionNotTheDraft() throws Exception {
        String pid = project();
        api.saveDraft(acme, DefinitionKind.DECISION, "risk", inProject(decision("DRAFT-ONLY"), pid), api.getDraft(acme, DefinitionKind.DECISION, "risk").revision());
        var r = releases.release(acme, pid, null);
        assertFalse(unzip(r.zip()).get("src/main/resources/wayang/bundle.json").contains("DRAFT-ONLY"));
        api.publish(acme, DefinitionKind.DECISION, "risk");
        assertTrue(unzip(releases.release(acme, pid, null).zip()).get("src/main/resources/wayang/bundle.json").contains("DRAFT-ONLY"));
    }

    @Test
    void aDefinitionThatWasNeverPublishedStopsTheReleaseAndIsNamed() {
        String pid = project();
        api.saveDraft(acme, DefinitionKind.PROCESS, "second", inProject(process("second"), pid), null);
        var e = assertThrows(ApiException.class, () -> releases.release(acme, pid, null));
        assertTrue(e.getMessage().contains("Publish them first"));
        assertEquals(List.of("process second"), e.details());
    }

    @Test
    void thePlanDoesNotBuildAnythingAndOptionsAreValidated() {
        String pid = project();
        var plan = releases.plan(acme, pid, Map.of("target", "standalone", "artifactId", "my-app", "version", "2.0.0"));
        assertEquals("STANDALONE", plan.target());
        assertEquals("my-app", plan.artifactId());
        assertTrue(plan.files().contains("pom.xml") && !plan.files().contains("deploy/kubernetes.yaml"));
        assertTrue(plan.files().contains("src/main/resources/wayang/component-lock.json"));
        assertThrows(ApiException.class, () -> releases.plan(acme, pid, Map.of("groupId", "Not Valid")));
        assertThrows(ApiException.class, () -> releases.plan(acme, pid, Map.of("target", "LAMBDA")));
        assertThrows(ApiException.class, () -> releases.plan(acme, pid, Map.of("package", "com.class")));
    }

    @Test
    void permissionsAndTenantsAreRespected() {
        String pid = project();
        assertDoesNotThrow(() -> releases.plan(viewer, pid, null));
        assertThrows(ApiException.class, () -> releases.plan(other, pid, null), "another tenant has no such project");
        assertThrows(ApiException.class, () -> releases.plan(acme, "no-such-project", null));
        assertThrows(ApiException.class, () -> releases.plan(null, pid, null));
    }

    @Test
    void defaultArtifactNamesAreSafe() {
        assertEquals("shop-orders", ReleaseService.defaultArtifact("Shop Orders!"));
        assertEquals("release", ReleaseService.defaultArtifact("!!!"));
        assertEquals("release", ReleaseService.defaultArtifact(null));
        assertTrue(ReleaseService.defaultArtifact("x".repeat(200)).length() <= 60);
        assertEquals("a-b", ReleaseService.defaultArtifact("  A / B  "));
    }
}
