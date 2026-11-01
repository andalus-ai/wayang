package tech.kayys.wayang.embed;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessMaps;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

class EmbedTest {

    /** start -> greet (service, with a secret and a setting) -> approve (user task) -> end */
    private static Map<String, Object> hello() {
        ProcessModel m = ProcessModel.builder("hello").name("Hello").start("s")
                .task("greet", ElementType.SERVICE_TASK, Map.of("connector", "greet", "inputs", Map.of("name", "person"),
                        "apiKey", "${secret:GREET_KEY}", "greeting", "${config:GREETING}"))
                .task("approve", ElementType.USER_TASK, Map.of("title", "Approve {{greet.text}}"))
                .end("e").flow("s", "greet").flow("greet", "approve").flow("approve", "e").build();
        return ProcessMaps.toMap(m);
    }

    private static Map<String, Object> simple() {
        ProcessModel m = ProcessModel.builder("simple").name("Simple").start("s")
                .task("greet", ElementType.SERVICE_TASK, Map.of("connector", "greet", "inputs", Map.of("name", "person")))
                .end("e").flow("s", "greet").flow("greet", "e").build();
        return ProcessMaps.toMap(m);
    }

    static String bundle(boolean published, List<String> requiredSecrets) {
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("format", "wayang.workspace");
        b.put("formatVersion", 1);
        b.put("project", Map.of("name", "Demo"));
        List<Object> defs = new ArrayList<>();
        defs.add(Map.of("kind", "PROCESS", "id", "hello", "publishedVersion", published ? 3 : 0, "document", hello()));
        defs.add(Map.of("kind", "PROCESS", "id", "simple", "publishedVersion", 1, "document", simple()));
        b.put("definitions", defs);
        b.put("config", Map.of("project", Map.of("GREETING", "Hi"), "tenant", Map.of("GREETING", "Yo", "OTHER", "x")));
        b.put("requires", Map.of("secrets", requiredSecrets, "externalSecrets", List.of(), "oauthConnections", List.of(), "config", List.of("GREETING")));
        b.put("integrity", Map.of("sha256", "abc123"));
        return Json.write(b);
    }

    private final List<Map<String, Object>> seen = new ArrayList<>();
    private final Map<String, ServiceHandler.Connector> connectors = Map.of("greet", new ServiceHandler.Connector() {
        @Override
        public Map<String, Object> call(Map<String, Object> in) {
            return call(Map.of(), in);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) {
            seen.add(config);
            return Map.of("text", config.getOrDefault("greeting", "Hello") + " " + in.get("name"));
        }
    });

    private EmbeddedRuntime runtime(Map<String, String> env, Path secretsDir) {
        ReleaseBundle b = ReleaseBundle.parse(bundle(true, List.of("GREET_KEY")));
        return new EmbeddedRuntime(b, connectors, null, ReleaseSecrets.secrets(secretsDir, env::get), ReleaseSecrets.config(b.config(), env::get),
                Clock.systemUTC());
    }

    // ------------------------------------------------------------------ bundle

    @Test
    void theBundleIsParsedAndRefusedWhenItIsNotAReleaseOfPublishedVersions() {
        ReleaseBundle b = ReleaseBundle.parse(bundle(true, List.of("GREET_KEY")));
        assertEquals("Demo", b.name());
        assertEquals(2, b.definitions().size());
        assertEquals("abc123", b.sha256());
        assertEquals(List.of("GREET_KEY"), b.requiredSecrets());
        assertEquals("Hi", b.config().get("GREETING"), "the project's value wins over the tenant's");
        assertEquals("x", b.config().get("OTHER"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(bundle(false, List.of())), "a draft is not releasable");
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse("{\"format\":\"other\",\"formatVersion\":1}"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse("{\"format\":\"wayang.workspace\",\"formatVersion\":2}"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse("nope"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse("{\"format\":\"wayang.workspace\",\"formatVersion\":1,\"definitions\":[{\"kind\":\"PROCESS\"}]}"));
    }

    @Test
    void aProcessThatDoesNotCompileStopsTheReleaseAtStartup() {
        String bad = Json.write(Map.of("format", "wayang.workspace", "formatVersion", 1, "definitions",
                List.of(Map.of("kind", "PROCESS", "id", "broken", "publishedVersion", 1, "document", Map.of("id", "broken")))));
        assertThrows(IllegalArgumentException.class, () -> new EmbeddedRuntime(ReleaseBundle.parse(bad), Map.of(), null, null, null, Clock.systemUTC()));
    }

    // ------------------------------------------------------------------ runtime

    @Test
    void aRunUsesSecretsAndSettingsFromTheEnvironmentAndParksOnTheUserTask() {
        var rt = runtime(Map.of("WAYANG_SECRET_GREET_KEY", "k-123", "WAYANG_CONFIG_GREETING", "Hola"), null);
        var info = rt.start("hello", Map.of("person", "Ann"));
        assertEquals("WAITING", info.status());
        assertEquals("k-123", seen.get(0).get("apiKey"), "the connector got the real secret");
        assertEquals("Hola", seen.get(0).get("greeting"), "the environment overrides the bundled setting");
        assertFalse(Json.write(info.toMap()).contains("k-123"), "and the run never shows it");
        assertEquals(1, info.waiting().size());
        assertTrue(info.variables().keySet().stream().noneMatch(k -> k.startsWith("wayang.")), "internal variables stay internal");
        String token = (String) info.waiting().get(0).get("token");
        assertEquals("Approve Hola Ann", info.waiting().get(0).get("title"));
        assertTrue(rt.completeWait(token, Map.of("approved", true)));
        assertEquals("COMPLETED", rt.get(info.id()).orElseThrow().status());
        assertFalse(rt.completeWait(token, Map.of()), "a task completes once");
    }

    @Test
    void theBundledSettingIsTheFallbackAndAMissingSecretFailsTheRunWithoutShowingAnything() {
        var rt = runtime(Map.of(), null);
        assertEquals(List.of("GREET_KEY"), rt.missingSecrets());
        var info = rt.start("hello", Map.of("person", "Ann"));
        assertEquals("FAILED", info.status());
        assertNotNull(info.error());
        rt = runtime(Map.of("WAYANG_SECRET_GREET_KEY", "k"), null);
        assertTrue(rt.missingSecrets().isEmpty());
        rt.start("hello", Map.of("person", "Bo"));
        assertEquals("Hi", seen.get(seen.size() - 1).get("greeting"));
    }

    @Test
    void callersCannotSpoofTheTenantOrProcessVariablesAndUnknownProcessesAreRefused() {
        var rt = runtime(Map.of("WAYANG_SECRET_GREET_KEY", "k"), null);
        var info = rt.start("simple", Map.of("person", "Ann", "wayang.tenant", "evil", "wayang.process", "evil"));
        assertEquals("COMPLETED", info.status());
        assertThrows(IllegalArgumentException.class, () -> rt.start("nope", Map.of()));
        assertEquals(List.of("hello", "simple"), List.copyOf(rt.processIds()));
        assertEquals(3, rt.version("hello"));
    }

    @Test
    void runsAreListedNewestFirstAndOnlyAParkedRunCanBeCancelled() {
        var rt = runtime(Map.of("WAYANG_SECRET_GREET_KEY", "k"), null);
        var a = rt.start("simple", Map.of("person", "A"));
        var b = rt.start("hello", Map.of("person", "B"));
        assertEquals(List.of(b.id(), a.id()), rt.list(null, 10).stream().map(EmbeddedRuntime.RunInfo::id).toList());
        assertEquals(List.of(a.id()), rt.list("simple", 10).stream().map(EmbeddedRuntime.RunInfo::id).toList());
        assertEquals(1, rt.list(null, 1).size());
        assertFalse(rt.cancel(a.id()));
        assertTrue(rt.cancel(b.id()));
        assertEquals("CANCELLED", rt.get(b.id()).orElseThrow().status());
        assertFalse(rt.completeWait((String) b.waiting().get(0).get("token"), Map.of()), "a cancelled task cannot be completed");
        assertFalse(rt.cancel("unknown"));
    }

    // ------------------------------------------------------------------ secrets

    @Test
    void secretsComeFromAFileFirstThenTheEnvironmentAndNeverFromOutsideTheDirectory() throws Exception {
        Path d = Files.createTempDirectory("sec");
        Files.writeString(d.resolve("FROM_FILE"), "file-value\n");
        Files.writeString(d.resolve("BOTH"), "from-file");
        Path outside = Files.createTempFile("out", ".txt");
        Files.writeString(outside, "leak");
        Files.createSymbolicLink(d.resolve("ESC"), outside);
        Map<String, String> env = Map.of("WAYANG_SECRET_BOTH", "from-env", "WAYANG_SECRET_ONLY_ENV", "e", "WAYANG_SECRET_A_B_C", "dots");
        var s = ReleaseSecrets.secrets(d, env::get);
        assertEquals("file-value", s.resolve("release", "FROM_FILE"));
        assertEquals("from-file", s.resolve("release", "BOTH"));
        assertEquals("e", s.resolve("release", "ONLY_ENV"));
        assertEquals("dots", s.resolve("release", "a.b-c"), "dots and dashes become underscores in the variable name");
        assertNull(s.resolve("release", "ESC"));
        assertNull(s.resolve("release", "../x"));
        assertNull(s.resolve("release", "vault.X"));
        assertNull(s.resolve("release", "aws.X"));
        assertNull(s.resolve("release", "NOPE"));
        assertNull(ReleaseSecrets.secrets(null, Map.of("WAYANG_SECRET_E", "")::get).resolve("release", "E"), "empty is missing");
        assertEquals("override", ReleaseSecrets.config(Map.of("K", "bundled"), Map.of("WAYANG_CONFIG_K", "override")::get).resolve("release", "p", "K"));
        assertNull(ReleaseSecrets.config(Map.of(), k -> null).resolve("release", "p", "bad name"));
    }

    // ------------------------------------------------------------------ HTTP

    private record Resp(int status, Map<String, Object> body, String raw) {
    }

    @SuppressWarnings("unchecked")
    private static Resp call(HttpService s, String method, String path, String key, String ct, String body) throws Exception {
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + path))
                .method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            rb.header("Authorization", "Bearer " + key);
        }
        if (ct != null) {
            rb.header("Content-Type", ct);
        }
        HttpResponse<String> r = HttpClient.newHttpClient().send(rb.build(), HttpResponse.BodyHandlers.ofString());
        Object o = r.body().isEmpty() ? Map.of() : Json.read(r.body());
        return new Resp(r.statusCode(), o instanceof Map ? (Map<String, Object>) o : Map.of(), r.body());
    }

    @Test
    void theServiceRunsProcessesBehindAnApiKey() throws Exception {
        var rt = runtime(Map.of("WAYANG_SECRET_GREET_KEY", "k"), null);
        try (var s = new HttpService(rt, "sekret", new InetSocketAddress("127.0.0.1", 0), 4096).start()) {
            assertEquals(200, call(s, "GET", "/healthz", null, null, null).status());
            assertEquals(200, call(s, "GET", "/readyz", null, null, null).status());
            assertEquals(401, call(s, "GET", "/v1/release", null, null, null).status());
            assertEquals(401, call(s, "GET", "/v1/release", "wrong", null, null).status());
            Resp rel = call(s, "GET", "/v1/release", "sekret", null, null);
            assertEquals(200, rel.status());
            assertEquals("abc123", rel.body().get("bundleSha256"));

            Resp r = call(s, "POST", "/v1/processes/hello/runs", "sekret", "application/json", "{\"input\":{\"person\":\"Ann\"}}");
            assertEquals(201, r.status(), r.raw());
            assertEquals("WAITING", r.body().get("status"));
            String id = (String) r.body().get("id");
            String token = (String) ((Map<?, ?>) ((List<?>) r.body().get("waiting")).get(0)).get("token");
            assertEquals(200, call(s, "GET", "/v1/runs/" + id, "sekret", null, null).status());
            assertEquals(404, call(s, "GET", "/v1/runs/nope", "sekret", null, null).status());
            assertEquals(1, ((List<?>) call(s, "GET", "/v1/runs?processId=hello&limit=5", "sekret", null, null).body().get("runs")).size());
            Resp done = call(s, "POST", "/v1/tasks/" + token + "/complete", "sekret", "application/json", "{\"outputs\":{\"ok\":true}}");
            assertEquals(200, done.status());
            assertEquals("COMPLETED", call(s, "GET", "/v1/runs/" + id, "sekret", null, null).body().get("status"));
            assertEquals(404, call(s, "POST", "/v1/tasks/" + token + "/complete", "sekret", "application/json", "{}").status());
            assertFalse(r.raw().contains("\"k\""));
        }
    }

    @Test
    void theServiceRefusesBadRequestsWithoutLeakingInternals() throws Exception {
        var rt = runtime(Map.of(), null);
        try (var s = new HttpService(rt, "sekret", new InetSocketAddress("127.0.0.1", 0), 2048).start()) {
            assertEquals(503, call(s, "GET", "/readyz", null, null, null).status(), "a missing secret means not ready");
            assertEquals(1, call(s, "GET", "/readyz", null, null, null).body().get("missingSecrets") instanceof Number n ? n.intValue() : -1);
            assertEquals(404, call(s, "POST", "/v1/processes/nope/runs", "sekret", "application/json", "{}").status());
            assertEquals(400, call(s, "POST", "/v1/processes/hello%20x/runs", "sekret", "application/json", "{}").status());
            assertEquals(400, call(s, "POST", "/v1/processes/hello/runs", "sekret", "application/json", "{bad").status());
            assertEquals(400, call(s, "POST", "/v1/processes/hello/runs", "sekret", "application/json", "[1]").status());
            assertEquals(400, call(s, "POST", "/v1/processes/hello/runs", "sekret", "application/json", "{\"input\":5}").status());
            assertEquals(415, call(s, "POST", "/v1/processes/hello/runs", "sekret", "text/plain", "{}").status());
            assertEquals(413, call(s, "POST", "/v1/processes/hello/runs", "sekret", "application/json", "{\"input\":{\"x\":\"" + "a".repeat(3000) + "\"}}").status());
            assertEquals(405, call(s, "DELETE", "/v1/runs/x", "sekret", null, null).status());
            assertEquals(405, call(s, "GET", "/v1/processes/hello/runs", "sekret", null, null).status());
            assertEquals(404, call(s, "GET", "/etc/passwd", "sekret", null, null).status());
            assertEquals(400, call(s, "GET", "/v1/runs?limit=abc", "sekret", null, null).status());
            assertEquals(409, call(s, "POST", "/v1/runs/x/cancel", "sekret", null, null).status());
            // a failing run is a normal 201 with FAILED status and a message, never a stack trace
            Resp r = call(s, "POST", "/v1/processes/hello/runs", "sekret", "application/json", "{\"input\":{\"person\":\"A\"}}");
            assertEquals("FAILED", r.body().get("status"));
            assertFalse(r.raw().contains("at tech.kayys"));
        }
    }

    @Test
    void withoutAKeyTheServiceOnlyStartsOnLoopback() throws Exception {
        var rt = runtime(Map.of("WAYANG_SECRET_GREET_KEY", "k"), null);
        assertThrows(IllegalStateException.class, () -> new HttpService(rt, null, new InetSocketAddress("0.0.0.0", 0), 4096));
        assertThrows(IllegalStateException.class, () -> new HttpService(rt, "  ", new InetSocketAddress("0.0.0.0", 0), 4096));
        try (var s = new HttpService(rt, null, new InetSocketAddress("127.0.0.1", 0), 4096).start()) {
            assertEquals(200, call(s, "GET", "/v1/release", null, null, null).status(), "no key configured: loopback is open");
        }
        assertDoesNotThrow(() -> new HttpService(rt, "k", new InetSocketAddress("0.0.0.0", 0), 4096));
    }

    // ------------------------------------------------------------------ CLI

    @Test
    void theCliRunsOnceAndReportsWithExitCodes() throws Exception {
        var rt = runtime(Map.of("WAYANG_SECRET_GREET_KEY", "k"), null);
        var out = new java.io.ByteArrayOutputStream();
        var err = new java.io.ByteArrayOutputStream();
        var po = new java.io.PrintStream(out);
        var pe = new java.io.PrintStream(err);
        java.io.InputStream in = new java.io.ByteArrayInputStream("{\"person\":\"Cy\"}".getBytes());
        assertEquals(0, Cli.run(new String[] { "run", "simple", "--input", "-" }, rt, k -> null, in, po, pe));
        assertTrue(out.toString().contains("\"status\":\"COMPLETED\""));
        assertTrue(out.toString().contains("Hi Cy") || out.toString().contains("Hello Cy"));
        out.reset();
        assertEquals(2, Cli.run(new String[] { "run", "hello", "--input", "-" }, rt, k -> null, new java.io.ByteArrayInputStream("{\"person\":\"D\"}".getBytes()), po, pe));
        assertTrue(out.toString().contains("WAITING"));
        assertEquals(64, Cli.run(new String[] { "run", "nope" }, rt, k -> null, in, po, pe));
        assertEquals(64, Cli.run(new String[] { "bogus" }, rt, k -> null, in, po, pe));
        assertEquals(64, Cli.run(new String[0], rt, k -> null, in, po, pe));
        assertEquals(64, Cli.run(new String[] { "run", "simple", "--input", "-", "--wait-seconds", "x" }, rt, k -> null, in, po, pe));
        assertEquals(0, Cli.run(new String[] { "check" }, rt, k -> null, in, po, pe));
        assertEquals(1, Cli.run(new String[] { "check" }, runtime(Map.of(), null), k -> null, in, po, pe));
        assertEquals(0, Cli.run(new String[] { "info" }, rt, k -> null, in, po, pe));
        assertEquals(64, Cli.run(new String[] { "serve" }, rt, k -> k.equals("WAYANG_PORT") ? "x" : null, in, po, pe), "a bad port is a usage error");
        assertEquals(64, Cli.run(new String[] { "serve" }, rt, k -> null, in, po, pe), "0.0.0.0 without a key is refused");
    }

    private static String sweepBundle(String sweepTo) {
        Map<String, Object> collect = Map.of("type", "collect", "key", "'all'", "size", 3, "timeoutSeconds", 60, "sweepTo", sweepTo);
        Map<String, Object> orders = Map.of("id", "orders", "name", "Orders", "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "gather", "type", "EIP_TASK", "config", Map.of("message", "order", "pipeline", Map.of("steps", List.of(collect)))),
                Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "gather"), Map.of("id", "f2", "source", "gather", "target", "e")));
        Map<String, Object> late = Map.of("id", "late", "name", "Late", "elements", List.of(Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "look", "type", "USER_TASK", "config", Map.of("title", "Look")), Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "look"), Map.of("id", "f2", "source", "look", "target", "e")));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("format", "wayang.workspace");
        b.put("formatVersion", 1);
        b.put("project", Map.of("name", "Sweep"));
        b.put("definitions", List.of(Map.of("kind", "PROCESS", "id", "orders", "publishedVersion", 1, "document", orders),
                Map.of("kind", "PROCESS", "id", "late", "publishedVersion", 1, "document", late)));
        b.put("config", Map.of());
        b.put("requires", Map.of("secrets", List.of(), "externalSecrets", List.of(), "oauthConnections", List.of(), "config", List.of()));
        b.put("integrity", Map.of("sha256", "abc123"));
        return Json.write(b);
    }

    @Test
    void aCollectGroupPastItsDeadlineStartsTheLateProcessOnTheServiceTick() {
        var rt = new EmbeddedRuntime(ReleaseBundle.parse(sweepBundle("late")), Map.of(), null, null, null,
                Clock.offset(Clock.systemUTC(), java.time.Duration.ofSeconds(120)));
        rt.start("orders", Map.of("order", "o-1"));
        rt.start("orders", Map.of("order", "o-2"));
        assertTrue(rt.list("late", 10).isEmpty());
        assertEquals(1, rt.fireDueTimers());
        var late = rt.list("late", 10);
        assertEquals(1, late.size());
        assertEquals("WAITING", late.get(0).status());
        var agg = (Map<?, ?>) late.get(0).variables().get("aggregate");
        assertEquals(List.of("o-1", "o-2"), agg.get("bodies"));
        assertEquals(Boolean.TRUE, agg.get("incomplete"));
        assertEquals(0, rt.fireDueTimers(), "a swept group is not started twice");
    }

    @Test
    void aSweepToAProcessTheReleaseDoesNotCarryIsRefusedWhenTheBundleLoads() {
        var e = assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(sweepBundle("nowhere")));
        assertTrue(e.getMessage().contains("nowhere"), e.getMessage());
    }
}
