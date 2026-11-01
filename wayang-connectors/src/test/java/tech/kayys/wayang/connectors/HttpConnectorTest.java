package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.wayang.bpmn.ElementType;
import tech.kayys.wayang.bpmn.ProcessCompiler;
import tech.kayys.wayang.bpmn.ProcessModel;
import tech.kayys.wayang.runtime.ControlHandler;
import tech.kayys.wayang.runtime.NodeRunner;
import tech.kayys.wayang.runtime.ReferenceEngine;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;

/** Runs the connector against a real local HTTP server (JDK com.sun.net.httpserver), not a mock. */
class HttpConnectorTest {

    private HttpServer server;
    private String base;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private int slowCalls;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/echo", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + ex.getRequestHeaders().getFirst("Authorization")
                    + " ct=" + ex.getRequestHeaders().getFirst("Content-Type") + " body=" + body);
            byte[] out = "{\"echo\":true,\"n\":42}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.createContext("/text", ex -> reply(ex, 200, "text/plain", "hello"));
        server.createContext("/missing", ex -> reply(ex, 404, "text/plain", "nope"));
        server.createContext("/busy", ex -> reply(ex, 503, "text/plain", "try later"));
        server.createContext("/limited", ex -> reply(ex, 429, "text/plain", "slow down"));
        server.createContext("/redirect", ex -> {
            ex.getResponseHeaders().add("Location", "http://127.0.0.1:1/");
            ex.sendResponseHeaders(302, -1);
            ex.close();
        });
        server.createContext("/big", ex -> reply(ex, 200, "text/plain", "x".repeat(5000)));
        server.createContext("/slow", ex -> {
            slowCalls++;
            try {
                Thread.sleep(1500);
            } catch (InterruptedException ignored) {
                Thread.currentThread().interrupt();
            }
            reply(ex, 200, "text/plain", "late");
        });
        server.createContext("/badjson", ex -> reply(ex, 200, "application/json", "{oops"));
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, int code, String type, String text) throws IOException {
        byte[] b = text.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", type);
        ex.sendResponseHeaders(code, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private HttpConnector local() {
        return new HttpConnector(new UrlGuard(true, Set.of()));
    }

    private static ServiceHandler.ConnectorException fail(HttpConnector c, Map<String, Object> cfg, Map<String, Object> in) {
        return assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(cfg, in));
    }

    @Test
    void postsJsonWithHeadersAndParsesJsonResponse() throws Exception {
        var out = local().call(Map.of("url", base + "/echo", "method", "post", "headers", Map.of("Authorization", "Bearer tok"),
                "query", Map.of("a", "x y")), Map.of("body", Map.of("k", 1)));
        assertEquals(200, out.get("status"));
        assertEquals(Map.of("echo", true, "n", 42L).get("echo"), ((Map<?, ?>) out.get("body")).get("echo"));
        String s = seen.get(0);
        assertTrue(s.startsWith("POST /echo?a=x+y"), s);
        assertTrue(s.contains("auth=Bearer tok") && s.contains("ct=application/json") && s.contains("\"k\":1"), s);
    }

    @Test
    void textResponseAndDynamicInputsMerge() throws Exception {
        var out = local().call(Map.of("url", base + "/text"), Map.of());
        assertEquals("hello", out.get("body"));
        local().call(Map.of("url", base + "/echo", "headers", Map.of("Authorization", "cfg")),
                Map.of("headers", Map.of("Authorization", "run")));
        assertTrue(seen.get(0).contains("auth=run"), "inputs override config headers");
    }

    @Test
    void getIgnoresBodyAndStringBodyIsSentAsIs() throws Exception {
        local().call(Map.of("url", base + "/echo"), Map.of("body", Map.of("k", 1)));
        assertTrue(seen.get(0).contains("body="), seen.get(0));
        assertFalse(seen.get(0).contains("\"k\""));
        local().call(Map.of("url", base + "/echo", "method", "PUT"), Map.of("body", "raw text"));
        assertTrue(seen.get(1).startsWith("PUT") && seen.get(1).endsWith("body=raw text"), seen.get(1));
    }

    @Test
    void statusMappingToRetryability() {
        assertFalse(fail(local(), Map.of("url", base + "/missing"), Map.of()).retryable());
        assertTrue(fail(local(), Map.of("url", base + "/busy"), Map.of()).retryable());
        assertTrue(fail(local(), Map.of("url", base + "/limited"), Map.of()).retryable());
        assertTrue(fail(local(), Map.of("url", base + "/missing"), Map.of()).getMessage().contains("HTTP 404: nope"));
    }

    @Test
    void redirectsAreNotFollowed() {
        var e = fail(local(), Map.of("url", base + "/redirect"), Map.of());
        assertTrue(e.getMessage().startsWith("HTTP 302"), e.getMessage());
    }

    @Test
    void limitsAndTimeouts() {
        var big = fail(local(), Map.of("url", base + "/big", "maxResponseBytes", 100), Map.of());
        assertFalse(big.retryable());
        assertTrue(big.getMessage().contains("larger"));
        var slow = fail(local(), Map.of("url", base + "/slow", "timeoutMs", 200), Map.of());
        assertTrue(slow.retryable() && slow.getMessage().contains("timed out"), slow.getMessage());
        assertFalse(fail(local(), Map.of("url", base + "/text", "timeoutMs", 999999), Map.of()).retryable(), "out of range");
        assertFalse(fail(local(), Map.of("url", base + "/badjson"), Map.of()).retryable());
    }

    @Test
    void invalidConfigIsNotRetryable() {
        assertFalse(fail(local(), Map.of(), Map.of()).retryable());
        assertFalse(fail(local(), Map.of("url", base, "method", "TRACE"), Map.of()).retryable());
        assertFalse(fail(local(), Map.of("url", base + "/text", "headers", Map.of("X", "a\r\nInjected: 1")), Map.of()).retryable());
        assertFalse(fail(local(), Map.of("url", base + "/text", "headers", Map.of("Host", "evil")), Map.of()).retryable());
        assertEquals(0, seen.size());
    }

    @Test
    void strictGuardRefusesInternalTargets() {
        HttpConnector strict = new HttpConnector(UrlGuard.strict());
        for (String u : List.of(base + "/text", "http://localhost/x", "http://169.254.169.254/latest/meta-data/",
                "http://10.0.0.5/", "http://192.168.1.1/", "http://[::1]/", "http://[fd00::1]/", "http://0.0.0.0/",
                "http://100.64.0.1/", "http://[::ffff:127.0.0.1]/", "file:///etc/passwd", "ftp://example.com/",
                "http://user:pw@example.com/")) {
            var e = fail(strict, Map.of("url", u), Map.of());
            assertFalse(e.retryable(), u);
        }
        assertEquals(0, seen.size(), "nothing reached the server");
    }

    @Test
    void allowListPermitsNamedHostsOnly() throws Exception {
        var c = new HttpConnector(new UrlGuard(false, Set.of("127.0.0.1")));
        assertEquals("hello", c.call(Map.of("url", base + "/text"), Map.of()).get("body"));
        assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("url", "http://localhost:" + server.getAddress().getPort() + "/text"), Map.of()));
    }

    /** Whole path: process definition -> secret resolution -> real HTTP call -> output usable by the next node. */
    @Test
    void endToEndProcessWithSecretHeader() {
        var reg = new WaitRegistry.InMemory();
        var runner = new NodeRunner(List.of(new ControlHandler(reg), new ServiceHandler(Map.of("http", local()))))
                .withSecrets((tenant, name) -> tenant.equals("acme") && name.equals("API_TOKEN") ? "s3cr3t-token" : null);
        var model = ProcessModel.builder("p").start("s")
                .task("call", ElementType.SERVICE_TASK, Map.of("connector", "http", "url", base + "/echo", "method", "POST",
                        "headers", Map.of("Authorization", "Bearer ${secret:API_TOKEN}"),
                        "inputs", Map.of("body", "{amount: amount}")))
                .end("e").flow("s", "call").flow("call", "e").build();
        var run = new ReferenceEngine(runner, reg).start(ProcessCompiler.compile(model),
                Map.of("wayang.tenant", "acme", "amount", 7));
        assertEquals(ReferenceEngine.Status.COMPLETED, run.status(), String.valueOf(run.error()));
        assertTrue(seen.get(0).contains("auth=Bearer s3cr3t-token"), seen.get(0));
        assertTrue(seen.get(0).contains("\"amount\":7"), seen.get(0));
        assertEquals(200, run.variables().get("call.status"));
        assertFalse(String.valueOf(run.variables()).contains("s3cr3t-token"), "secret must not land in run variables");

        var other = new ReferenceEngine(runner, reg).start(ProcessCompiler.compile(model), Map.of("wayang.tenant", "globex"));
        assertEquals(ReferenceEngine.Status.FAILED, other.status(), "another tenant cannot use acme's secret");
        assertEquals(1, seen.size());
    }

    @Test
    void failedCallRetriesPerNodePolicyOnlyWhenRetryable() {
        var reg = new WaitRegistry.InMemory();
        var runner = new NodeRunner(List.of(new ControlHandler(reg), new ServiceHandler(Map.of("http", local()))));
        var cfg404 = Map.<String, Object>of("connector", "http", "url", base + "/missing", "retries", 3, "retryDelay", "PT0S");
        var m = ProcessModel.builder("p").start("s").task("c", ElementType.SERVICE_TASK, cfg404).end("e")
                .flow("s", "c").flow("c", "e").build();
        var run = new ReferenceEngine(runner, reg).start(ProcessCompiler.compile(m), Map.of());
        assertEquals(ReferenceEngine.Status.FAILED, run.status());
        assertTrue(run.error().contains("404"));
        assertNotNull(slowCalls);
        assertNotNull(new ArrayList<>(seen));
    }
}
