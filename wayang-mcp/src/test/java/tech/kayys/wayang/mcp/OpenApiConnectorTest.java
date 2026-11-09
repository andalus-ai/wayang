package tech.kayys.wayang.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicLong;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;

/** The {@code openapi} connector: only a document (inline or by URL) and an operation, against a fake API that serves its own document. */
class OpenApiConnectorTest {

    private HttpServer api;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private String base;
    private String spec;
    private final AtomicLong now = new AtomicLong(1_000_000);
    private OpenApiConnector connector;

    private static void reply(HttpExchange ex, int status, String type, String body) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        byte[] b = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, b.length == 0 ? -1 : b.length);
        if (b.length > 0) {
            ex.getResponseBody().write(b);
        }
        ex.close();
    }

    @BeforeEach
    void start() throws IOException {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", ex -> {
            String raw = ex.getRequestURI().getRawPath() + (ex.getRequestURI().getRawQuery() == null ? "" : "?" + ex.getRequestURI().getRawQuery());
            ex.getRequestBody().readAllBytes();
            seen.add(ex.getRequestMethod() + " " + raw + " auth=" + ex.getRequestHeaders().getFirst("Authorization"));
            if (raw.equals("/spec.json")) {
                reply(ex, 200, "application/json", spec);
            } else if (raw.equals("/v1/pets/404")) {
                reply(ex, 404, "application/json", "{\"error\":\"no such pet\"}");
            } else if (raw.startsWith("/v1/pets/") && ex.getRequestMethod().equals("GET")) {
                reply(ex, 200, "application/json", "{\"id\":7,\"name\":\"Rex\"}");
            } else if (raw.startsWith("/v1/pets") && ex.getRequestMethod().equals("GET")) {
                reply(ex, 200, "application/json", "[{\"id\":1},{\"id\":2}]");
            } else if (raw.startsWith("/v1/pets") && ex.getRequestMethod().equals("POST")) {
                reply(ex, 201, "application/json", "{\"id\":99}");
            } else {
                reply(ex, 500, "text/plain", "boom");
            }
        });
        api.start();
        base = "http://127.0.0.1:" + api.getAddress().getPort() + "/v1";
        spec = OpenApiToMcpTest.OAS3.replace("https://{env}.pets.example/v1", base).replace("\"variables\": {\"env\": {\"default\": \"api\"}}", "\"x\": 1");
        connector = new OpenApiConnector(new UrlGuard(true, Set.of()), now::get);
    }

    @AfterEach
    void stop() {
        api.stop(0);
    }

    private Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void aSpecUrlAloneIsEnoughToDiscoverAndCallAnOperation() throws Exception {
        String url = "http://127.0.0.1:" + api.getAddress().getPort() + "/spec.json";
        Map<String, Object> list = connector.call(cfg("specUrl", url, "operation", "listOperations"), Map.of());
        List<?> ops = (List<?>) list.get("operations");
        assertEquals(List.of("listPets", "getPet"), ops.stream().map(o -> ((Map<?, ?>) o).get("name")).toList(), "read-only by default");
        assertTrue(((Map<?, ?>) ops.get(1)).get("inputSchema").toString().contains("petId"));

        Map<String, Object> out = connector.call(cfg("specUrl", url, "operation", "getPet"), Map.of("petId", "7"));
        assertEquals(Map.of("id", 7, "name", "Rex"), ((Map<?, ?>) out.get("result")).entrySet().stream()
                .collect(java.util.stream.Collectors.toMap(e -> e.getKey(), e -> e.getValue() instanceof Number n ? n.intValue() : e.getValue())));
        assertEquals(Boolean.FALSE, out.get("isError"));
        assertTrue(seen.contains("GET /v1/pets/7 auth=null"), seen.toString());
    }

    @Test
    void anInlineSpecWorksAndWritesNeedToBeNamed() throws Exception {
        Map<String, Object> list = connector.call(cfg("spec", spec, "operation", "listOperations", "methods", List.of("GET", "POST")), Map.of());
        assertTrue(list.get("operations").toString().contains("createPet"));
        assertThrows(ServiceHandler.ConnectorException.class, () -> connector.call(cfg("spec", spec, "operation", "createPet"), Map.of("body", Map.of("name", "x"))),
                "a POST is not available unless 'methods' names it");
        Map<String, Object> out = connector.call(cfg("spec", spec, "operation", "createPet", "methods", List.of("POST")), Map.of("body", Map.of("name", "Fido")));
        assertTrue(out.get("text").toString().contains("99"));
        assertTrue(seen.stream().anyMatch(s -> s.startsWith("POST /v1/pets")), seen.toString());
    }

    @Test
    void credentialsAreSentOnlyToABaseUrlTheBuilderChose() throws Exception {
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> connector.call(
                cfg("spec", spec, "operation", "getPet", "headers", Map.of("Authorization", "Bearer s3cret")), Map.of("petId", "7")));
        assertTrue(e.getMessage().contains("baseUrl"), e.getMessage());
        assertFalse(e.getMessage().contains("s3cret"));
        assertTrue(seen.isEmpty(), "nothing was sent");
        connector.call(cfg("spec", spec, "operation", "getPet", "baseUrl", base, "headers", Map.of("Authorization", "Bearer s3cret")), Map.of("petId", "7"));
        assertTrue(seen.contains("GET /v1/pets/7 auth=Bearer s3cret"), seen.toString());
    }

    @Test
    void apiErrorsFailTheNodeUnlessAskedNotTo() throws Exception {
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> connector.call(cfg("spec", spec, "operation", "getPet"), Map.of("petId", "404")));
        assertTrue(e.getMessage().contains("getPet"), e.getMessage());
        Map<String, Object> out = connector.call(cfg("spec", spec, "operation", "getPet", "failOnError", false), Map.of("petId", "404"));
        assertEquals(Boolean.TRUE, out.get("isError"));
        assertThrows(ServiceHandler.ConnectorException.class, () -> connector.call(cfg("spec", spec, "operation", "getPet"), Map.of()), "missing required argument");
        assertThrows(ServiceHandler.ConnectorException.class, () -> connector.call(cfg("spec", spec, "operation", "getPet"), Map.of("petId", "..")));
        connector.call(cfg("spec", spec, "operation", "getPet"), Map.of("petId", "../x"));
        assertTrue(seen.stream().anyMatch(x -> x.startsWith("GET /v1/pets/..%2Fx")), "a path value is encoded into its one segment: " + seen);
    }

    @Test
    void badConfigurationIsRefusedBeforeAnyRequest() {
        Map<String, Object> both = cfg("spec", spec, "specUrl", "http://x.example/s", "operation", "getPet");
        for (Map<String, Object> c : List.of(both, cfg("operation", "getPet"), cfg("spec", spec), cfg("spec", "not a document", "operation", "getPet"),
                cfg("spec", spec, "operation", "nope"), cfg("spec", spec, "operation", "getPet", "methods", List.of("TRACE")),
                cfg("spec", spec, "operation", "getPet", "headers", Map.of("X-A", "a\r\nX-B: b"), "baseUrl", base),
                cfg("spec", spec, "operation", "getPet", "headers", Map.of("bad name", "a"), "baseUrl", base),
                cfg("spec", "x".repeat(OpenApiConnector.MAX_SPEC_BYTES + 1), "operation", "getPet"))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> connector.call(c, Map.of("petId", "1")), c.keySet().toString());
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    @Test
    void theDocumentIsFetchedOncePerMinute() throws Exception {
        String url = "http://127.0.0.1:" + api.getAddress().getPort() + "/spec.json";
        connector.call(cfg("specUrl", url, "operation", "getPet"), Map.of("petId", "1"));
        connector.call(cfg("specUrl", url, "operation", "getPet"), Map.of("petId", "2"));
        assertEquals(1, seen.stream().filter(s -> s.startsWith("GET /spec.json")).count());
        now.addAndGet(61_000);
        connector.call(cfg("specUrl", url, "operation", "getPet"), Map.of("petId", "3"));
        assertEquals(2, seen.stream().filter(s -> s.startsWith("GET /spec.json")).count());
    }

    @Test
    void theSsrfGuardStillAppliesToTheDocumentAndTheCall() {
        OpenApiConnector strict = new OpenApiConnector(new UrlGuard(false, Set.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> strict.call(cfg("specUrl", "http://127.0.0.1:1/spec.json", "operation", "listOperations"), Map.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> strict.call(cfg("spec", spec, "operation", "getPet"), Map.of("petId", "1")),
                "the document's own server URL is a private address");
    }
}
