package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.TimeUnit;

import javax.tools.JavaCompiler;
import javax.tools.ToolProvider;

import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessMaps;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.runtime.json.Json;

/**
 * The generator's output is compiled with javac against the built modules and run in a separate JVM: what it generates really
 * starts, checks, runs a process, and serves. Not covered here (no Maven or Docker in the environment): the pom, the Dockerfile
 * and the Kubernetes manifest.
 */
class PublishTest {

    private static final ReleaseOptions OPTS = new ReleaseOptions(ReleaseOptions.Target.SERVICE, "com.acme", "my-release", "1.2.3", "com.acme.release", "0.0.1", 8080);

    /** start -> fetch (http connector, url from a setting, header from a secret) -> approve (user task) -> end; plus a connector-free process. */
    static String bundle(boolean withConnector, boolean withAgent, String name) {
        ProcessModel.Builder b = ProcessModel.builder("report").name("Report").start("s");
        if (withConnector) {
            b.task("fetch", ElementType.SERVICE_TASK, Map.of("connector", "http", "url", "${config:API_URL}", "method", "GET",
                    "headers", Map.of("Authorization", "Bearer ${secret:API_TOKEN}")));
            b.task("approve", ElementType.USER_TASK, Map.of("title", "Check the report"));
            b.end("e").flow("s", "fetch").flow("fetch", "approve").flow("approve", "e");
        } else {
            b.task("approve", ElementType.USER_TASK, Map.of("title", "Check the report"));
            b.end("e").flow("s", "approve").flow("approve", "e");
        }
        List<Object> defs = new ArrayList<>();
        defs.add(Map.of("kind", "PROCESS", "id", "report", "publishedVersion", 4, "document", ProcessMaps.toMap(b.build())));
        if (withAgent) {
            ProcessModel a = ProcessModel.builder("ask").name("Ask").start("s").task("think", ElementType.AGENT_TASK, Map.of("agent", "helper"))
                    .end("e").flow("s", "think").flow("think", "e").build();
            defs.add(Map.of("kind", "PROCESS", "id", "ask-1", "publishedVersion", 1, "document", ProcessMaps.toMap(a)));
        }
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("format", "wayang.workspace");
        m.put("formatVersion", 1);
        m.put("project", Map.of("name", name));
        m.put("definitions", defs);
        m.put("config", Map.of("project", Map.of("API_URL", "http://example.invalid/")));
        m.put("requires", Map.of("secrets", withConnector ? List.of("API_TOKEN") : List.of(), "externalSecrets", List.of(), "oauthConnections", List.of(),
                "config", withConnector ? List.of("API_URL") : List.of()));
        m.put("integrity", Map.of("sha256", "deadbeef"));
        return Json.write(m);
    }

    private static String text(Publisher.Project p, String path) {
        return new String(p.files().get(path), StandardCharsets.UTF_8);
    }

    // ------------------------------------------------------------------ what is generated

    @Test
    void aConnectorFreeReleaseDependsOnTheEmbeddedRuntimeAlone() {
        var p = Publisher.generate(bundle(false, false, "Plain"), OPTS);
        String pom = text(p, "pom.xml");
        assertTrue(pom.contains("<artifactId>wayang-embed</artifactId>"));
        assertFalse(pom.contains("wayang-connectors"), "nothing calls a connector, so the connector library is not a dependency");
        assertFalse(pom.contains("quarkus") || pom.contains("api-core") || pom.contains("wayang-api"));
        assertTrue(p.connectors().isEmpty());
        assertFalse(text(p, "src/main/java/com/acme/release/App.java").contains("Connectors."));
        assertFalse(p.files().containsKey("src/main/java/com/acme/release/Agents.java"));
        assertEquals(List.of("report"), p.processes());
        assertEquals(List.of("wayang.bpmn.control-flow", "wayang.human.task"), p.components());
        assertTrue(p.files().containsKey("src/main/resources/wayang/component-lock.json"));
        assertTrue(text(p, "README.md").contains("Resolved components:"));
        assertTrue(p.files().containsKey("deploy/kubernetes.yaml"));
    }

    @Test
    void aConnectorUsingReleaseAddsTheConnectorLibraryAndWiresOnlyTheOnesItCalls() {
        var p = Publisher.generate(bundle(true, false, "Fetcher"), OPTS);
        assertTrue(text(p, "pom.xml").contains("<artifactId>wayang-connectors</artifactId>"));
        assertEquals(List.of("http"), p.connectors());
        assertEquals(List.of("wayang.bpmn.control-flow", "wayang.human.task", "wayang.service.connector"), p.components());
        Map<?, ?> lock = (Map<?, ?>) Json.read(text(p, "src/main/resources/wayang/component-lock.json"));
        assertEquals("wayang.component-lock", lock.get("format"));
        assertEquals(1, ((Number) lock.get("formatVersion")).intValue());
        assertTrue(lock.get("sha256") instanceof String sha && sha.matches("[0-9a-f]{64}"));
        assertEquals(p.components(), ((List<?>) lock.get("components")).stream()
                .map(x -> ((Map<?, ?>) x).get("id").toString()).toList());
        Map<?, ?> releaseMeta = (Map<?, ?>) Json.read(text(p, "src/main/resources/wayang/release.json"));
        assertEquals(p.components(), releaseMeta.get("components"));
        assertEquals(lock.get("sha256"), releaseMeta.get("componentLockSha256"));
        assertEquals(List.of("API_TOKEN"), p.requiredSecrets());
        assertTrue(text(p, "src/main/java/com/acme/release/Processes.java").contains("Set.of(\"http\")"));
        assertTrue(text(p, ".env.example").contains("WAYANG_SECRET_API_TOKEN="));
        assertTrue(text(p, ".env.example").contains("# WAYANG_CONFIG_API_URL=http://example.invalid/"));
        assertFalse(new String(p.files().get("src/main/resources/wayang/bundle.json"), StandardCharsets.UTF_8).contains("Bearer abc"));
    }

    @Test
    void agentTasksRunThroughTheBuiltInModelLoopAndSayWhichKeysTheyNeed() {
        var p = Publisher.generate(bundle(false, true, "Agentic"), OPTS);
        assertTrue(p.usesAgents());
        assertTrue(p.files().containsKey("src/main/java/com/acme/release/Agents.java"));
        assertTrue(text(p, "src/main/java/com/acme/release/App.java").contains("Agents.invoker(env, secrets, config, connectors)"));
        assertTrue(text(p, "src/main/java/com/acme/release/Agents.java").contains("new LlmAgentInvoker(connectors, secrets, config, env)"));
        assertTrue(p.notes().stream().anyMatch(n -> n.contains("agent")));
        assertTrue(text(p, "src/main/java/com/acme/release/Processes.java").contains("ASK_1 = \"ask-1\""));
    }

    @Test
    void theStandaloneTargetHasNoServerFilesAndDefaultsToInfo() {
        var o = new ReleaseOptions(ReleaseOptions.Target.STANDALONE, "com.acme", "tool", "1.0.0", "com.acme.tool", "0.0.1", 8080);
        var p = Publisher.generate(bundle(false, false, "Tool"), o);
        assertFalse(p.files().containsKey("deploy/kubernetes.yaml"));
        assertTrue(text(p, "src/main/java/com/acme/tool/App.java").contains("{ \"info\" }"));
        assertFalse(text(p, ".env.example").contains("WAYANG_API_KEY"));
        assertFalse(text(p, "README.md").contains("## HTTP"));
    }

    @Test
    void theSameBundleAndOptionsGiveByteIdenticalOutput() throws Exception {
        var a = Publisher.generate(bundle(true, true, "Same"), OPTS);
        var b = Publisher.generate(bundle(true, true, "Same"), OPTS);
        assertEquals(a.files().keySet(), b.files().keySet());
        a.files().forEach((k, v) -> assertTrue(java.util.Arrays.equals(v, b.files().get(k)), k));
        assertTrue(java.util.Arrays.equals(Publisher.zip(a), Publisher.zip(b)));
        try (var z = new java.util.zip.ZipInputStream(new java.io.ByteArrayInputStream(Publisher.zip(a)))) {
            int n = 0;
            for (var e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                assertEquals(java.time.LocalDateTime.of(1980, 1, 1, 0, 0), e.getTimeLocal(), e.getName());
                n++;
            }
            assertEquals(a.files().size(), n);
        }
        var c = Publisher.generate(bundle(true, true, "Other"), OPTS);
        assertFalse(java.util.Arrays.equals(Publisher.zip(a), Publisher.zip(c)));
    }

    @Test
    void anythingThatCannotBeReleasedIsRefusedWithAReason() {
        String noProcess = Json.write(Map.of("format", "wayang.workspace", "formatVersion", 1, "definitions", List.of()));
        assertThrows(IllegalArgumentException.class, () -> Publisher.generate(noProcess, OPTS));
        ProcessModel m = ProcessModel.builder("x").name("X").start("s").task("t", ElementType.SERVICE_TASK, Map.of("connector", "nope-connector"))
                .end("e").flow("s", "t").flow("t", "e").build();
        String unknown = Json.write(Map.of("format", "wayang.workspace", "formatVersion", 1, "definitions",
                List.of(Map.of("kind", "PROCESS", "id", "x", "publishedVersion", 1, "document", ProcessMaps.toMap(m)))));
        var e = assertThrows(IllegalArgumentException.class, () -> Publisher.generate(unknown, OPTS));
        assertTrue(e.getMessage().contains("nope-connector"));
        String vaultSecret = Json.write(Map.of("format", "wayang.workspace", "formatVersion", 1, "definitions",
                List.of(Map.of("kind", "PROCESS", "id", "x", "publishedVersion", 1, "document", ProcessMaps.toMap(ProcessModel.builder("x").name("X").start("s").end("e").flow("s", "e").build()))),
                "requires", Map.of("secrets", List.of(), "externalSecrets", List.of("vault.X"))));
        assertThrows(IllegalArgumentException.class, () -> Publisher.generate(vaultSecret, OPTS));
        String draft = bundle(false, false, "D").replace("\"publishedVersion\":4", "\"publishedVersion\":0");
        assertThrows(IllegalArgumentException.class, () -> Publisher.generate(draft, OPTS));
    }

    @Test
    void optionsAreValidatedBecauseTheyEndUpInSourceAndXml() {
        var t = ReleaseOptions.Target.SERVICE;
        assertThrows(IllegalArgumentException.class, () -> new ReleaseOptions(t, "Com.Acme", "a", "1", "com.acme", "1", 80));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseOptions(t, "com.acme", "A B", "1", "com.acme", "1", 80));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseOptions(t, "com.acme", "a", "1<", "com.acme", "1", 80));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseOptions(t, "com.acme", "a", "1", "com.acme;evil", "1", 80));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseOptions(t, "com.acme", "a", "1", "com.class", "1", 80));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseOptions(t, "com.acme", "a", "1", "com.acme", "1", 0));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseOptions(t, "com.acme", "a", "1", "com.acme", "1", 70000));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseOptions(null, "com.acme", "a", "1", "com.acme", "1", 80));
        assertThrows(IllegalArgumentException.class, () -> ReleaseOptions.of(Map.of("target", "LAMBDA"), "a", "1"));
        var d = ReleaseOptions.of(Map.of(), "my-app", "0.0.1");
        assertEquals("com.example.myapp", d.packageName());
        assertEquals(ReleaseOptions.Target.SERVICE, d.target());
    }

    @Test
    void aHostileProjectNameCannotInjectIntoThePomJavaOrMarkdown() {
        String evil = "X</name><evil/> */ class Y {} \"\n# pwned";
        var p = Publisher.generate(bundle(false, false, evil), OPTS);
        assertFalse(text(p, "pom.xml").contains("<evil/>"));
        assertFalse(text(p, "README.md").contains("\n# pwned"));
        for (var e : p.files().entrySet()) {
            if (e.getKey().endsWith(".java")) {
                assertFalse(new String(e.getValue(), StandardCharsets.UTF_8).contains("pwned"), e.getKey());
            }
        }
    }

    @Test
    void processIdsBecomeSafeUniqueJavaConstants() {
        ProcessModel one = ProcessModel.builder("a-b").name("A").start("s").end("e").flow("s", "e").build();
        ProcessModel two = ProcessModel.builder("a.b").name("B").start("s").end("e").flow("s", "e").build();
        ProcessModel three = ProcessModel.builder("9lives").name("C").start("s").end("e").flow("s", "e").build();
        String b = Json.write(Map.of("format", "wayang.workspace", "formatVersion", 1, "definitions", List.of(
                Map.of("kind", "PROCESS", "id", "a-b", "publishedVersion", 1, "document", ProcessMaps.toMap(one)),
                Map.of("kind", "PROCESS", "id", "a.b", "publishedVersion", 2, "document", ProcessMaps.toMap(two)),
                Map.of("kind", "PROCESS", "id", "9lives", "publishedVersion", 3, "document", ProcessMaps.toMap(three)))));
        String src = text(Publisher.generate(b, OPTS), "src/main/java/com/acme/release/Processes.java");
        assertTrue(src.contains("A_B = \"a-b\"") && src.contains("A_B_2 = \"a.b\"") && src.contains("P_9LIVES = \"9lives\""), src);
    }

    // ------------------------------------------------------------------ the generated project really runs

    private static Path build(Publisher.Project p) throws Exception {
        Path root = Files.createTempDirectory("rel");
        for (var e : p.files().entrySet()) {
            Path f = root.resolve(e.getKey());
            Files.createDirectories(f.getParent());
            Files.write(f, e.getValue());
        }
        Path classes = root.resolve("target/classes");
        Files.createDirectories(classes);
        List<String> sources = new ArrayList<>();
        try (var s = Files.walk(root.resolve("src/main/java"))) {
            s.filter(x -> x.toString().endsWith(".java")).forEach(x -> sources.add(x.toString()));
        }
        JavaCompiler c = ToolProvider.getSystemJavaCompiler();
        List<String> args = new ArrayList<>(List.of("--release", "21", "-Xlint:all", "-Werror", "-cp", System.getProperty("java.class.path"), "-d", classes.toString()));
        args.addAll(sources);
        var err = new java.io.ByteArrayOutputStream();
        int rc = c.run(null, null, err, args.toArray(new String[0]));
        assertEquals(0, rc, "the generated sources must compile cleanly: " + err);
        try (var s = Files.walk(root.resolve("src/main/resources"))) {
            for (Path r : (Iterable<Path>) s.filter(Files::isRegularFile)::iterator) {
                Path dst = classes.resolve(root.resolve("src/main/resources").relativize(r));
                Files.createDirectories(dst.getParent());
                Files.copy(r, dst);
            }
        }
        return root;
    }

    private static Process start(Path root, Map<String, String> env, String... args) throws Exception {
        List<String> cmd = new ArrayList<>(List.of(Path.of(System.getProperty("java.home"), "bin", "java").toString(), "-cp",
                root.resolve("target/classes") + File.pathSeparator + System.getProperty("java.class.path"), "com.acme.release.App"));
        cmd.addAll(List.of(args));
        ProcessBuilder pb = new ProcessBuilder(cmd).redirectErrorStream(false);
        pb.environment().keySet().removeIf(k -> k.startsWith("WAYANG_"));
        pb.environment().putAll(env);
        return pb.start();
    }

    private static String out(Process p) throws Exception {
        assertTrue(p.waitFor(60, TimeUnit.SECONDS), "the release did not finish");
        return new String(p.getInputStream().readAllBytes(), StandardCharsets.UTF_8) + new String(p.getErrorStream().readAllBytes(), StandardCharsets.UTF_8);
    }

    @Test
    void theGeneratedReleaseChecksRunsAndParksOnTheCommandLine() throws Exception {
        HttpServer upstream = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        List<String> auth = new java.util.concurrent.CopyOnWriteArrayList<>();
        upstream.createContext("/data", ex -> {
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            byte[] b = "{\"ok\":true}".getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        upstream.start();
        try {
            Path root = build(Publisher.generate(bundle(true, false, "Fetcher"), OPTS));
            Map<String, String> env = Map.of("WAYANG_CONFIG_API_URL", "http://127.0.0.1:" + upstream.getAddress().getPort() + "/data",
                    "WAYANG_CONNECTORS_ALLOW_PRIVATE", "true");
            Process missing = start(root, env, "check");
            String o = out(missing);
            assertEquals(1, missing.exitValue(), "the secret is not provided yet: " + o);
            assertTrue(o.contains("API_TOKEN"));
            assertFalse(o.toLowerCase().contains("secretvalue"));

            Map<String, String> full = new LinkedHashMap<>(env);
            full.put("WAYANG_SECRET_API_TOKEN", "tok-xyz");
            Process ok = start(root, full, "check");
            out(ok);
            assertEquals(0, ok.exitValue());

            Process run = start(root, full, "run", "report");
            String r = out(run);
            assertEquals(2, run.exitValue(), "the run parks on the user task: " + r);
            assertTrue(r.contains("\"status\":\"WAITING\""), r);
            assertTrue(r.contains("Check the report"));
            assertFalse(r.contains("tok-xyz"), "the secret never appears in output");
            assertEquals(List.of("Bearer tok-xyz"), auth, "the connector really called out, with the secret from the environment");

            // no process, default command of the SERVICE target is serve: refused without a key on 0.0.0.0
            Process svc = start(root, Map.of("WAYANG_PORT", "0"));
            String s = out(svc);
            assertEquals(64, svc.exitValue(), s);
            assertTrue(s.contains("API key"));
        } finally {
            upstream.stop(0);
        }
    }

    @Test
    void theGeneratedServiceServesOverHttpWithItsApiKey() throws Exception {
        Path root = build(Publisher.generate(bundle(false, false, "Svc"), OPTS));
        Process p = start(root, Map.of("WAYANG_API_KEY", "k-1", "WAYANG_BIND", "127.0.0.1", "WAYANG_PORT", "18931"), "serve");
        try {
            HttpClient c = HttpClient.newHttpClient();
            String body = null;
            for (int i = 0; i < 100 && body == null; i++) {
                try {
                    body = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18931/healthz")).build(), HttpResponse.BodyHandlers.ofString()).body();
                } catch (java.io.IOException e) {
                    Thread.sleep(100);
                }
            }
            assertNotNull(body, "the service did not come up");
            var run = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18931/v1/processes/report/runs")).header("Authorization", "Bearer k-1")
                    .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString("{\"input\":{}}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(201, run.statusCode(), run.body());
            assertTrue(run.body().contains("WAITING"));
            var denied = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:18931/v1/release")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, denied.statusCode());
        } finally {
            p.destroy();
            p.waitFor(10, TimeUnit.SECONDS);
        }
    }

    // ------------------------------------------------------------------ triggers

    @SuppressWarnings("unchecked")
    private static String withTriggers(String bundle, List<Map<String, Object>> triggers, List<String> secrets) {
        Map<String, Object> m = (Map<String, Object>) Json.read(bundle);
        m.put("triggers", triggers);
        m.put("requires", Map.of("secrets", secrets, "externalSecrets", List.of(), "oauthConnections", List.of(), "config", List.of()));
        return Json.write(m);
    }

    private static Map<String, Object> trigger(String id, String kind, Map<String, Object> config) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("kind", kind);
        t.put("processId", "report");
        t.put("enabled", true);
        t.put("config", config);
        return t;
    }

    @Test
    void aScheduleTriggerNeedsNoSourceNoProviderAndNoSecret() {
        String b = withTriggers(bundle(false, false, "Clock"), List.of(
                trigger("brief", "SCHEDULE", Map.of("cron", "0 8 * * 1-5", "timezone", "Asia/Jakarta", "input", Map.of("topic", "news")))), List.of());
        var p = Publisher.generate(b, OPTS);
        String procs = text(p, "src/main/java/com/acme/release/Processes.java");
        assertTrue(procs.contains("POLL_SOURCES = Set.of()"), procs);
        assertTrue(p.requiredSecrets().isEmpty(), p.requiredSecrets().toString());
        assertTrue(text(p, "src/main/resources/wayang/release.json").contains("\"brief\""));
        assertThrows(IllegalArgumentException.class, () -> build0(withTriggers(bundle(false, false, "x"),
                List.of(trigger("t", "SCHEDULE", Map.of("cron", "nope"))), List.of())), "a bad cron is refused at generation");
    }

    @Test
    void triggersBringInTheConnectorLibraryAndWireOnlyTheTypesTheyUse() {
        String b = withTriggers(bundle(false, false, "Hooks"), List.of(
                trigger("gh", "WEBHOOK", Map.of("auth", "github", "secret", "${secret:GH_SECRET}", "allowedRepos", List.of("acme/app"))),
                trigger("feed", "POLL", Map.of("source", "rss", "url", "https://example.com/feed.xml")),
                trigger("plain", "WEBHOOK", Map.of())), List.of("GH_SECRET"));
        var p = Publisher.generate(b, OPTS);
        assertTrue(text(p, "pom.xml").contains("wayang-connectors"), "pollers and providers live in the connector library");
        assertTrue(p.connectors().isEmpty(), "no connector is wired for the workflows: they call none");
        String procs = text(p, "src/main/java/com/acme/release/Processes.java");
        assertTrue(procs.contains("POLL_SOURCES = Set.of(\"rss\")"), procs);
        assertTrue(procs.contains("WEBHOOK_PROVIDERS = Set.of(\"github\")"), procs);
        assertEquals(List.of("GH_SECRET", "trigger.plain"), p.requiredSecrets(), "the shared secret of a plain webhook is named, a provider one is not");
        assertTrue(p.notes().stream().anyMatch(n -> n.contains("/hooks/<id>")));
        assertTrue(text(p, ".env.example").contains("WAYANG_SECRET_TRIGGER_PLAIN=") && text(p, ".env.example").contains("WAYANG_STATE_DIR"));
        assertTrue(text(p, "src/main/resources/wayang/release.json").contains("\"triggers\""));
        assertThrows(IllegalArgumentException.class, () -> build0(withTriggers(bundle(false, false, "x"),
                List.of(trigger("t", "POLL", Map.of("source", "nosuch"))), List.of())), "an unknown poll source is refused at generation");
        assertThrows(IllegalArgumentException.class, () -> build0(withTriggers(bundle(false, false, "x"),
                List.of(trigger("t", "WEBHOOK", Map.of("auth", "nosuch"))), List.of())), "an unknown provider is refused at generation");
    }

    private static Publisher.Project build0(String bundle) {
        return Publisher.generate(bundle, OPTS);
    }

    @Test
    void aReleaseWithoutTriggersWiresNoPollersAndStillCompiles() throws Exception {
        build(Publisher.generate(bundle(false, false, "NoHooks"), OPTS));
        String app = text(Publisher.generate(bundle(false, false, "NoHooks"), OPTS), "src/main/java/com/acme/release/App.java");
        assertFalse(app.contains("Connectors."), "nothing of the connector library is touched");
        String wiring = text(Publisher.generate(bundle(false, false, "NoHooks"), OPTS), "src/main/java/com/acme/release/Wiring.java");
        assertFalse(wiring.contains("Connectors."), "nor in the wiring: " + wiring);
        assertTrue(wiring.contains("static Map<String, Poller> pollers(Function<String, String> env) {\n        return Map.of();"));
    }

    @Test
    void theGeneratedServiceRunsItsWebhookAndPollsItsFeed() throws Exception {
        HttpServer feed = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        feed.createContext("/feed.xml", ex -> {
            byte[] b = ("<rss version=\"2.0\"><channel><title>t</title><item><title>First</title><guid>g1</guid><link>http://x/1</link></item>"
                    + "<item><title>Second</title><guid>g2</guid><link>http://x/2</link></item></channel></rss>").getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/rss+xml");
            ex.sendResponseHeaders(200, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        feed.start();
        try {
            String b = withTriggers(bundle(false, false, "Live"), List.of(
                    trigger("feed", "POLL", Map.of("source", "rss", "url", "http://127.0.0.1:" + feed.getAddress().getPort() + "/feed.xml",
                            "emitExisting", true, "everySeconds", 5)),
                    trigger("plain", "WEBHOOK", Map.of())), List.of());
            Path root = build(Publisher.generate(b, OPTS));
            Path state = Files.createTempDirectory("state");
            Map<String, String> env = Map.of("WAYANG_API_KEY", "k-2", "WAYANG_BIND", "127.0.0.1", "WAYANG_PORT", "18932",
                    "WAYANG_CONNECTORS_ALLOW_PRIVATE", "true", "WAYANG_SECRET_TRIGGER_PLAIN", "hook-s3cret", "WAYANG_STATE_DIR", state.toString());
            Process p = start(root, env, "serve");
            try {
                HttpClient c = HttpClient.newHttpClient();
                String base = "http://127.0.0.1:18932";
                String up = null;
                for (int i = 0; i < 100 && up == null; i++) {
                    try {
                        up = c.send(HttpRequest.newBuilder(URI.create(base + "/healthz")).build(), HttpResponse.BodyHandlers.ofString()).body();
                    } catch (java.io.IOException e) {
                        Thread.sleep(100);
                    }
                }
                assertNotNull(up, "the service did not come up");
                var denied = c.send(HttpRequest.newBuilder(URI.create(base + "/hooks/plain")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                        HttpResponse.BodyHandlers.ofString());
                assertEquals(401, denied.statusCode());
                var ok = c.send(HttpRequest.newBuilder(URI.create(base + "/hooks/plain")).header("X-Wayang-Secret", "hook-s3cret")
                        .POST(HttpRequest.BodyPublishers.ofString("{\"order\":1}")).build(), HttpResponse.BodyHandlers.ofString());
                assertEquals(202, ok.statusCode(), ok.body());
                // the feed has two entries and emitExisting is on: one run each, started by the poller within a few seconds
                int runs = 0;
                for (int i = 0; i < 80 && runs < 3; i++) {
                    Thread.sleep(250);
                    var l = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/runs?processId=report&limit=50")).header("Authorization", "Bearer k-2").build(),
                            HttpResponse.BodyHandlers.ofString());
                    runs = ((List<?>) ((Map<?, ?>) Json.read(l.body())).get("runs")).size();
                }
                assertEquals(3, runs, "one run from the webhook and two from the feed");
                assertTrue(Files.isRegularFile(state.resolve("cursor-feed")), "the cursor was written to the state directory");
                var trig = c.send(HttpRequest.newBuilder(URI.create(base + "/v1/triggers")).header("Authorization", "Bearer k-2").build(), HttpResponse.BodyHandlers.ofString());
                assertTrue(trig.body().contains("\"feed\"") && !trig.body().contains("hook-s3cret"));
            } finally {
                p.destroy();
                p.waitFor(10, TimeUnit.SECONDS);
            }
        } finally {
            feed.stop(0);
        }
    }

    // ------------------------------------------------------------------ the Gamelan target

    private static final ReleaseOptions GAM = new ReleaseOptions(ReleaseOptions.Target.GAMELAN, "com.acme", "my-release", "1.2.3", "com.acme.release", "0.0.1", 8080);

    @Test
    void theGamelanTargetIsAQuarkusProjectThatHostsTheExecutorsAndServesTheSameApi() {
        String b = withTriggers(bundle(true, false, "Durable"), List.of(trigger("plain", "WEBHOOK", Map.of())), List.of("API_TOKEN"));
        var p = Publisher.generate(b, GAM);
        String pom = text(p, "pom.xml");
        assertTrue(pom.contains("quarkus-bom") && pom.contains("quarkus-maven-plugin") && pom.contains("<artifactId>quarkus-arc</artifactId>"), pom);
        assertTrue(pom.contains("wayang-gamelan-worker") && pom.contains("gamelan-sdk-executor-remote") && pom.contains("gamelan-sdk-client-remote"));
        assertTrue(pom.contains("<gamelan.version>0.1.1</gamelan.version>") && pom.contains("<quarkus.platform.version>3.32.2</quarkus.platform.version>"));
        assertTrue(pom.contains("wayang-connectors"), "the workflow calls the http connector");
        assertFalse(pom.contains("api-core") || pom.contains("wayang-adapter-gamelan"), "none of the design-time server comes along");
        assertFalse(p.files().containsKey("src/main/java/com/acme/release/App.java"), "no command line: Quarkus starts the service");
        for (String f : List.of("ReleaseWiring", "ReleaseApp", "Wiring", "Processes")) {
            assertTrue(p.files().containsKey("src/main/java/com/acme/release/" + f + ".java"), f);
        }
        String props = text(p, "src/main/resources/application.properties");
        assertTrue(props.contains("quarkus.index-dependency.wayang-worker.artifact-id=wayang-gamelan-worker"));
        assertTrue(props.contains("engine.grpc.endpoint=${WAYANG_GAMELAN_GRPC_HOST:localhost}") && props.contains("gamelan.executor.transport=GRPC"));
        assertFalse(props.contains("secret") && props.contains("="), "no secret value is written into the configuration: " + props.lines().filter(l -> l.toLowerCase().contains("secret")).toList());
        assertTrue(text(p, "Dockerfile").contains("quarkus-run.jar"));
        String k8s = text(p, "deploy/kubernetes.yaml");
        assertTrue(k8s.contains("WAYANG_GAMELAN_ENDPOINT") && k8s.contains("replicas: 1") && k8s.contains("emptyDir"), k8s);
        assertTrue(text(p, ".env.example").contains("WAYANG_GAMELAN_ENDPOINT") && text(p, ".env.example").contains("WAYANG_API_KEY"));
        assertTrue(text(p, "README.md").contains("quarkus-run.jar"));
        assertTrue(p.notes().stream().anyMatch(n -> n.contains("one replica")));
        assertTrue(p.notes().stream().anyMatch(n -> n.contains("never run against a Gamelan server")));
        assertEquals(List.of("API_TOKEN", "trigger.plain"), p.requiredSecrets());
        var meta = (Map<?, ?>) Json.read(text(p, "src/main/resources/wayang/release.json"));
        assertEquals("GAMELAN", meta.get("target"));
        assertEquals("0.1.1", meta.get("gamelanVersion"));
        assertEquals(List.of("plain"), meta.get("triggers"));
    }

    @Test
    void theGamelanTargetIsDeterministicAndItsVersionsAreOptionsThatAreValidated() throws Exception {
        String b = bundle(false, false, "Same");
        assertTrue(java.util.Arrays.equals(Publisher.zip(Publisher.generate(b, GAM)), Publisher.zip(Publisher.generate(b, GAM))));
        var o = ReleaseOptions.of(Map.of("target", "gamelan", "gamelanVersion", "0.2.0", "quarkusVersion", "3.33.0"), "x", "0.0.1");
        assertEquals(ReleaseOptions.Target.GAMELAN, o.target());
        assertTrue(text(Publisher.generate(b, o), "pom.xml").contains("<gamelan.version>0.2.0</gamelan.version>"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseOptions.of(Map.of("gamelanVersion", "1<2"), "x", "0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseOptions.of(Map.of("quarkusVersion", "a b"), "x", "0.0.1"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseOptions.of(Map.of("target", "lambda"), "x", "0.0.1"));
    }

    @Test
    void theGamelanTargetWithAgentsAndTriggersWiresThemTheSameWay() {
        String b = withTriggers(bundle(false, true, "Agentic"), List.of(trigger("feed", "POLL", Map.of("source", "rss", "url", "https://example.com/f"))), List.of());
        var p = Publisher.generate(b, GAM);
        assertTrue(text(p, "src/main/java/com/acme/release/ReleaseWiring.java").contains("Agents.invoker(env, secrets, config, connectors)"));
        assertTrue(text(p, "src/main/java/com/acme/release/Wiring.java").contains("Connectors.pollers"));
        assertTrue(text(p, "src/main/java/com/acme/release/ReleaseApp.java").contains("Wiring.pollers(env)"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aConnectorOnlyAnAgentToolUsesIsStillWiredIn() {
        Map<String, Object> m = (Map<String, Object>) Json.read(bundle(false, true, "Tools"));
        ((List<Object>) m.get("definitions")).add(Map.of("kind", "AGENT", "id", "helper", "publishedVersion", 1, "document", Map.of("id", "helper", "model", "ollama/x",
                "tools", List.of(Map.of("name", "lookup", "type", "http", "config", Map.of("url", "https://example.com/")),
                        Map.of("name", "odd", "type", "no-such-connector")))));
        var p = Publisher.generate(Json.write(m), OPTS);
        assertEquals(List.of("http"), p.connectors());
        assertTrue(text(p, "pom.xml").contains("wayang-connectors"));
        assertTrue(text(p, "src/main/java/com/acme/release/Processes.java").contains("\"http\""), "the http connector is listed for wiring");
    }

    @Test
    void aProfileRefusesReleasesThatNeedAFeatureItLacks() {
        ReleaseOptions integration = ReleaseOptions.of(Map.of("target", "GAMELAN", "profile", "integration"), "my-release", "0.0.1");
        assertEquals("integration", integration.profile());
        // the report process only has service + human tasks: human is not in 'integration'
        IllegalArgumentException e = assertThrows(IllegalArgumentException.class, () -> Publisher.generate(bundle(true, false, "x"), integration));
        assertTrue(e.getMessage().contains("'integration' profile") && e.getMessage().contains("approve"), e.getMessage());
        ReleaseOptions automation = ReleaseOptions.of(Map.of("target", "GAMELAN", "profile", "automation"), "my-release", "0.0.1");
        assertTrue(text(Publisher.generate(bundle(true, false, "x"), automation), "Dockerfile").contains("WAYANG_PROFILE=automation"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseOptions.of(Map.of("profile", "agnt\nRUN evil"), "a", "0.0.1"));
        assertEquals("full", OPTS.profile());
    }

    @Test
    @SuppressWarnings("unchecked")
    void anExtensionToolIsWiredInAndBringsItsModuleOnlyWhenUsed() {
        Map<String, Object> m = (Map<String, Object>) Json.read(bundle(false, true, "Pictures"));
        ((List<Object>) m.get("definitions")).add(Map.of("kind", "AGENT", "id", "painter", "publishedVersion", 1, "document", Map.of("id", "painter", "model", "ollama/x",
                "tools", List.of(Map.of("name", "draw", "type", "image-generate", "config", Map.of("model", "m", "apiKey", "${secret:IMAGE_API_KEY}"))))));
        for (ReleaseOptions o : List.of(OPTS, GAM)) {
            var p = Publisher.generate(Json.write(m), o);
            assertEquals(List.of("image-generate"), p.connectors());
            assertTrue(p.components().contains("wayang.multimodal.image-generate"), p.components().toString());
            assertTrue(p.components().contains("wayang.agent.invoke"), p.components().toString());
            assertTrue(text(p, "pom.xml").contains("wayang-multimodal"), o.target().toString());
        }
        var plain = Publisher.generate(bundle(true, false, "Plain"), OPTS);
        assertFalse(text(plain, "pom.xml").contains("wayang-multimodal"));
        // under a profile without the media feature the extension is not loaded, so its connector is unknown
        ReleaseOptions noMedia = ReleaseOptions.of(Map.of("profile", "agentic"), "my-release", "0.0.1");
        assertEquals(List.of("image-generate"), Publisher.generate(Json.write(m), noMedia).connectors(), "agentic has media");
    }

    @Test
    void anOpenapiNodeBringsTheMcpModuleInBothTargets() {
        // the same bundle as the http one, with the node turned into an openapi call (the spec link, an operation, fixed headers)
        String httpBundle = bundle(true, false, "Pets");
        assertTrue(httpBundle.contains("\"connector\":\"http\""), "bundle shape changed");
        String bundleJson = httpBundle.replace("\"connector\":\"http\"", "\"connector\":\"openapi\",\"specUrl\":\"https://api.example.com/openapi.json\",\"operation\":\"getPet\"");
        for (ReleaseOptions o : List.of(OPTS, GAM)) {
            var p = Publisher.generate(bundleJson, o);
            assertEquals(List.of("openapi"), p.connectors());
            assertTrue(p.components().contains("wayang.mcp.openapi"), p.components().toString());
            assertTrue(text(p, "pom.xml").contains("wayang-mcp"), o.target().toString());
            assertTrue(text(p, "src/main/resources/wayang/artifact-lock.json").contains("wayang-mcp"), o.target().toString());
        }
        assertFalse(text(Publisher.generate(bundle(true, false, "Plain"), OPTS), "pom.xml").contains("wayang-mcp"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void aTranscribeToolAlsoBringsTheMultimodalModule() {
        Map<String, Object> m = (Map<String, Object>) Json.read(bundle(false, true, "Ears"));
        ((List<Object>) m.get("definitions")).add(Map.of("kind", "AGENT", "id", "listener", "publishedVersion", 1, "document", Map.of("id", "listener", "model", "ollama/x",
                "tools", List.of(Map.of("name", "hear", "type", "transcribe", "config", Map.of("apiKey", "${secret:OPENAI_API_KEY}"))))));
        var p = Publisher.generate(Json.write(m), OPTS);
        assertEquals(List.of("transcribe"), p.connectors());
        assertTrue(text(p, "pom.xml").contains("wayang-multimodal"));
    }

    @Test
    void releaseContainsDeterministicInternalArtifactLockAndReportsCurrentRuntimeClosure() {
        var p = Publisher.generate(bundle(false, false, "Artifact lock"), OPTS);
        Map<?, ?> lock = (Map<?, ?>) Json.read(text(p, "src/main/resources/wayang/artifact-lock.json"));
        assertEquals("wayang.artifact-lock", lock.get("format"));
        assertEquals(1, ((Number) lock.get("formatVersion")).intValue());
        String artifactLockDigest = String.valueOf(lock.get("sha256"));
        assertTrue(artifactLockDigest.matches("[0-9a-f]{64}"));
        List<String> artifacts = ((List<?>) lock.get("artifacts")).stream()
                .map(item -> ((Map<?, ?>) item).get("artifactId").toString()).toList();
        assertTrue(artifacts.containsAll(List.of("wayang-embed", "wayang-runtime", "wayang-business-process",
                "wayang-dmn", "wayang-integration", "wayang-agent", "wayang-expression")));
        assertFalse(artifacts.contains("wayang-connectors"));
        assertTrue(text(p, "README.md").contains("artifact-lock.json"));
        Map<?, ?> release = (Map<?, ?>) Json.read(text(p, "src/main/resources/wayang/release.json"));
        assertEquals(artifacts, release.get("artifacts"));
        assertEquals(artifactLockDigest, release.get("artifactLockSha256"));

        var again = Publisher.generate(bundle(false, false, "Artifact lock"), OPTS);
        assertEquals(text(p, "src/main/resources/wayang/artifact-lock.json"),
                text(again, "src/main/resources/wayang/artifact-lock.json"));
    }

    @Test
    void connectorAndMultimodalComponentsExpandToTheirInternalArtifactDependencies() {
        var connector = Publisher.generate(bundle(true, false, "Connector artifact"), OPTS);
        Map<?, ?> connectorLock = (Map<?, ?>) Json.read(text(connector, "src/main/resources/wayang/artifact-lock.json"));
        List<String> connectorArtifacts = ((List<?>) connectorLock.get("artifacts")).stream()
                .map(item -> ((Map<?, ?>) item).get("artifactId").toString()).toList();
        assertTrue(connectorArtifacts.contains("wayang-connectors"));

        Map<String, Object> m = (Map<String, Object>) Json.read(bundle(false, true, "Multimodal artifact"));
        ((List<Object>) m.get("definitions")).add(Map.of("kind", "AGENT", "id", "painter", "publishedVersion", 1,
                "document", Map.of("id", "painter", "model", "ollama/x",
                        "tools", List.of(Map.of("name", "draw", "type", "image-generate", "config", Map.of("model", "m"))))));
        var multimodal = Publisher.generate(Json.write(m), OPTS);
        Map<?, ?> multimodalLock = (Map<?, ?>) Json.read(text(multimodal, "src/main/resources/wayang/artifact-lock.json"));
        List<String> multimodalArtifacts = ((List<?>) multimodalLock.get("artifacts")).stream()
                .map(item -> ((Map<?, ?>) item).get("artifactId").toString()).toList();
        assertTrue(multimodalArtifacts.contains("wayang-multimodal"));
        assertTrue(multimodalArtifacts.contains("wayang-connectors"));
    }

    @Test
    void gamelanArtifactLockUsesWorkerAsItsDeploymentRoot() {
        var p = Publisher.generate(bundle(false, false, "Gamelan artifact"), GAM);
        Map<?, ?> lock = (Map<?, ?>) Json.read(text(p, "src/main/resources/wayang/artifact-lock.json"));
        List<String> roots = ((List<?>) lock.get("roots")).stream().map(Object::toString).toList();
        assertTrue(roots.contains("wayang-gamelan-worker"), "the Gamelan worker is the deployment entry point");
        assertTrue(roots.containsAll(List.of("wayang-business-process", "wayang-embed")),
                "the workflow component roots remain explicit alongside the deployment entry point");
        List<String> artifacts = ((List<?>) lock.get("artifacts")).stream()
                .map(item -> ((Map<?, ?>) item).get("artifactId").toString()).toList();
        assertTrue(artifacts.containsAll(List.of("wayang-gamelan-worker", "wayang-embed", "wayang-runtime")));
    }

}
