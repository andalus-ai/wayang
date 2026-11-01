package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.mcp.OpenApiExecutor;
import tech.kayys.wayang.runtime.json.Json;

class McpServiceTest {

    private HttpServer api;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private String base;
    private final Map<String, String> secretValues = new LinkedHashMap<>();
    private final Map<String, String> specs = new LinkedHashMap<>();

    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal designer = new Principal("acme", "dee", Set.of(Role.DESIGNER));
    private final Principal operator = new Principal("acme", "ola", Set.of(Role.OPERATOR));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));
    private final Principal otherAdmin = new Principal("globex", "gus", Set.of(Role.ADMIN));

    private McpService svc;

    private static String spec(String server, String extraPath) {
        return """
                {"openapi":"3.0.0","info":{"title":"Pets","version":"3"},"servers":[{"url":"%s"}],"paths":{
                 "/pets/{id}":{"get":{"operationId":"getPet","parameters":[{"name":"id","in":"path","required":true,"schema":{"type":"string"}}],
                   "responses":{"200":{"description":"ok"}}}}%s}}
                """.formatted(server, extraPath);
    }

    @BeforeEach
    void start() throws IOException {
        api = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        api.createContext("/", ex -> {
            String auth = ex.getRequestHeaders().getFirst("Authorization");
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " auth=" + auth + " key=" + ex.getRequestURI().getRawQuery());
            String body = "{\"echoedAuth\":\"" + auth + "\",\"ok\":true}";
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, body.length());
            ex.getResponseBody().write(body.getBytes(StandardCharsets.UTF_8));
            ex.close();
        });
        api.start();
        base = "http://127.0.0.1:" + api.getAddress().getPort();
        secretValues.put("acme/PETS", "s3cr3t-value");
        specs.put("https://specs.example/pets.json", spec(base, ""));
        svc = new McpService(new McpStore.InMemory(), url -> {
            String s = specs.get(url);
            if (s == null) {
                throw new IOException("404");
            }
            return s;
        }, new OpenApiExecutor(new UrlGuard(true, Set.of())), (t, n) -> secretValues.get(t + "/" + n), Clock.systemUTC());
    }

    @AfterEach
    void stop() {
        api.stop(0);
    }

    private static McpService.Request inline(String spec, Map<String, String> headers) {
        return new McpService.Request(spec, null, null, headers, Map.of(), null, null, null, null, null, null, null);
    }

    private static McpService.Request url(String url, Map<String, String> headers) {
        return new McpService.Request(null, url, null, headers, Map.of(), null, null, null, null, null, null, null);
    }

    private static String call(String tool, Map<String, Object> args) {
        return Json.write(Map.of("jsonrpc", "2.0", "id", 1, "method", "tools/call", "params", Map.of("name", tool, "arguments", args)));
    }

    @Test
    @SuppressWarnings("unchecked")
    void generatesStoresAndServesWithTheCallersPermissions() {
        McpServerDef d = svc.create(admin, "pets", inline(spec(base, ""), Map.of("Authorization", "Bearer ${secret:PETS}")));
        assertEquals(1, d.tools().size());
        assertEquals(base, d.baseUrl());
        assertEquals("Bearer ${secret:PETS}", d.headers().get("Authorization"), "stored as a template, not a value");

        String list = svc.rpc(viewer, "pets", "{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertTrue(list.contains("\"getPet\""));
        String denied = svc.rpc(viewer, "pets", call("getPet", Map.of("id", "1")));
        assertTrue(denied.contains("\"isError\":true") && denied.contains("RUN_START"), denied);
        assertTrue(seen.isEmpty(), "a viewer cannot run tools");

        String ok = svc.rpc(operator, "pets", call("getPet", Map.of("id", "42")));
        Map<String, Object> res = (Map<String, Object>) ((Map<String, Object>) Json.read(ok)).get("result");
        assertEquals(false, res.get("isError"));
        assertTrue(seen.get(0).contains("auth=Bearer s3cr3t-value"), "the credential is resolved per call: " + seen.get(0));
        assertFalse(ok.contains("s3cr3t-value"), "and masked if the API echoes it back: " + ok);
        assertTrue(ok.contains("Bearer ***"));
        assertEquals("Bearer ***", ((Map<String, Object>) res.get("structuredContent")).get("echoedAuth"));
    }

    @Test
    void aMissingSecretFailsTheCallWithoutReachingTheApi() {
        svc.create(admin, "pets", inline(spec(base, ""), Map.of("Authorization", "Bearer ${secret:NOT_SET}")));
        String r = svc.rpc(operator, "pets", call("getPet", Map.of("id", "1")));
        assertTrue(r.contains("\"isError\":true") && r.contains("credential"), r);
        assertTrue(seen.isEmpty());
    }

    @Test
    void credentialsMustBeSecretReferencesAndHeadersMustBeSane() {
        for (Map<String, String> h : List.of(
                Map.of("Authorization", "Bearer literal-token"),
                Map.of("X-Api-Key", "abc"),
                Map.of("Bad Name", "x"),
                Map.of("Host", "evil.example"),
                Map.of("X-Trace", "a\r\nX-Evil: 1"))) {
            assertThrows(ApiException.class, () -> svc.create(admin, "p", inline(spec(base, ""), h)), h.toString());
        }
        var q = new McpService.Request(spec(base, ""), null, null, Map.of(), Map.of("api_key", "literal"), null, null, null, null, null, null, null);
        assertThrows(ApiException.class, () -> svc.create(admin, "p", q));
        svc.create(admin, "ok1", inline(spec(base, ""), Map.of("X-Env", "prod")));
        var q2 = new McpService.Request(spec(base, ""), null, null, Map.of(), Map.of("api_key", "${secret:PETS}"), null, null, null, null, null, null, null);
        svc.create(admin, "ok2", q2);
        svc.rpc(operator, "ok2", call("getPet", Map.of("id", "1")));
        assertTrue(seen.get(0).contains("key=api_key=s3cr3t-value"), seen.get(0));
    }

    @Test
    void rbacAndTenantIsolation() {
        assertThrows(ApiException.class, () -> svc.create(viewer, "p", inline(spec(base, ""), Map.of())));
        assertThrows(ApiException.class, () -> svc.create(operator, "p", inline(spec(base, ""), Map.of())));
        assertThrows(ApiException.class, () -> svc.preview(viewer, inline(spec(base, ""), Map.of())));
        svc.create(designer, "pets", inline(spec(base, ""), Map.of()));
        assertEquals(1, svc.list(viewer).size());
        assertThrows(ApiException.class, () -> svc.delete(viewer, "pets"));
        assertEquals(0, svc.list(otherAdmin).size());
        assertEquals(ApiException.Code.NOT_FOUND, assertThrows(ApiException.class, () -> svc.get(otherAdmin, "pets")).code());
        assertEquals(ApiException.Code.NOT_FOUND, assertThrows(ApiException.class, () -> svc.rpc(otherAdmin, "pets", "{}")).code());
        assertEquals(ApiException.Code.NOT_FOUND, assertThrows(ApiException.class, () -> svc.delete(otherAdmin, "pets")).code());
        svc.create(otherAdmin, "pets", inline(spec(base, ""), Map.of()));
        svc.delete(designer, "pets");
        assertEquals(1, svc.list(otherAdmin).size(), "deleting one tenant's server leaves the other's");
    }

    @Test
    void previewStoresNothingAndValidationIsClear() {
        var r = svc.preview(designer, inline(spec(base, ""), Map.of()));
        assertEquals(1, r.tools().size());
        assertTrue(svc.list(admin).isEmpty());

        assertThrows(ApiException.class, () -> svc.create(admin, "bad id!", inline(spec(base, ""), Map.of())));
        assertThrows(ApiException.class, () -> svc.create(admin, "x", new McpService.Request(null, null, null, Map.of(), Map.of(), null, null, null, null, null, null, null)));
        assertThrows(ApiException.class, () -> svc.create(admin, "x", new McpService.Request("{}", "https://specs.example/pets.json", null, Map.of(), Map.of(), null, null, null, null, null, null, null)));
        assertThrows(ApiException.class, () -> svc.create(admin, "x", url("ftp://specs.example/pets.json", Map.of())));
        assertEquals(ApiException.Code.BAD_REQUEST, assertThrows(ApiException.class, () -> svc.create(admin, "x", inline("not a spec", Map.of()))).code());
        assertEquals(ApiException.Code.UNAVAILABLE, assertThrows(ApiException.class, () -> svc.create(admin, "x", url("https://specs.example/missing.json", Map.of()))).code());
        // relative server URL and no origin to resolve it against
        assertEquals(ApiException.Code.BAD_REQUEST, assertThrows(ApiException.class, () -> svc.create(admin, "x", inline(spec("/api", ""), Map.of()))).code());
        // everything filtered out
        var none = new McpService.Request(spec(base, ""), null, null, Map.of(), Map.of(), null, null, null, List.of("^/nothing"), null, null, null);
        assertEquals(ApiException.Code.VALIDATION_FAILED, assertThrows(ApiException.class, () -> svc.create(admin, "x", none)).code());
        svc.create(admin, "dup", inline(spec(base, ""), Map.of()));
        assertEquals(ApiException.Code.CONFLICT, assertThrows(ApiException.class, () -> svc.create(admin, "dup", inline(spec(base, ""), Map.of()))).code());
    }

    @Test
    void relativeServerUrlsResolveAgainstTheSpecUrlAndRefreshRegenerates() {
        specs.put("https://specs.example/pets.json", spec("/v9", ""));
        var req = new McpService.Request(null, "https://specs.example/pets.json", base, Map.of(), Map.of(), null, null, null, null, null, null, null);
        McpServerDef d = svc.create(admin, "pets", req);
        assertEquals(base, d.baseUrl(), "explicit baseUrl wins");
        assertEquals(1, d.tools().size());

        specs.put("https://specs.example/pets.json", spec("/v9", ",\"/owners\":{\"get\":{\"operationId\":\"listOwners\",\"responses\":{\"200\":{\"description\":\"ok\"}}}}"));
        McpServerDef n = svc.refresh(admin, "pets");
        assertEquals(2, n.tools().size());
        assertEquals(base, n.baseUrl(), "options survive a refresh");
        assertEquals(2, svc.get(viewer, "pets").tools().size());

        specs.put("https://specs.example/pets.json", "{\"openapi\":\"3.0.0\",\"info\":{\"title\":\"x\",\"version\":\"1\"},\"paths\":{\"/a\":{\"post\":{\"responses\":{}}}}}");
        assertThrows(ApiException.class, () -> svc.refresh(admin, "pets"), "a refresh that yields no tools does not wipe the old ones");
        assertEquals(2, svc.get(viewer, "pets").tools().size());

        svc.create(admin, "inline", inline(spec(base, ""), Map.of()));
        assertThrows(ApiException.class, () -> svc.refresh(admin, "inline"));
    }

    @Test
    void theStoreContractHoldsForTheInMemoryImplementation() {
        tech.kayys.wayang.api.testkit.McpStoreContract.verify(McpStore.InMemory::new);
    }
}
