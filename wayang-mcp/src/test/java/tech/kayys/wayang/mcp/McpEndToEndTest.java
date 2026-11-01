package tech.kayys.wayang.mcp;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.connectors.Mcp;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Fake REST API  <-  OpenApiExecutor  <-  McpServerCore (served over HTTP)  <-  Mcp.Client (the workflow connector).
 */
class McpEndToEndTest {

    private HttpServer api;
    private HttpServer mcpHost;
    private final List<String> apiSeen = new CopyOnWriteArrayList<>();
    private final List<String> mcpSeen = new CopyOnWriteArrayList<>();
    private volatile boolean sse;
    private volatile boolean useSession;
    private McpServerCore core;
    private String apiBase;
    private String mcpUrl;

    private static String body(HttpExchange ex) throws IOException {
        return new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
    }

    private static void reply(HttpExchange ex, int status, String type, String body, String... headers) throws IOException {
        ex.getResponseHeaders().add("Content-Type", type);
        for (int i = 0; i < headers.length; i += 2) {
            ex.getResponseHeaders().add(headers[i], headers[i + 1]);
        }
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
            String b = body(ex);
            apiSeen.add(ex.getRequestMethod() + " " + raw + " auth=" + ex.getRequestHeaders().getFirst("Authorization")
                    + " ct=" + ex.getRequestHeaders().getFirst("Content-Type") + " trace=" + ex.getRequestHeaders().getFirst("X-Trace") + " body=" + b);
            String path = ex.getRequestURI().getRawPath();
            if (path.equals("/v1/pets/404")) {
                reply(ex, 404, "application/json", "{\"error\":\"no such pet\"}");
            } else if (path.startsWith("/v1/pets/") && ex.getRequestMethod().equals("GET")) {
                reply(ex, 200, "application/json", "{\"id\":7,\"name\":\"Rex\",\"owner\":{\"name\":\"Ann\"}}");
            } else if (path.equals("/v1/pets") && ex.getRequestMethod().equals("GET")) {
                reply(ex, 200, "application/json", "[{\"id\":1},{\"id\":2}]");
            } else if (path.equals("/v1/pets") && ex.getRequestMethod().equals("POST")) {
                reply(ex, 201, "application/json", "{\"id\":99,\"name\":\"New\"}");
            } else {
                reply(ex, 500, "text/plain", "boom");
            }
        });
        api.start();
        apiBase = "http://127.0.0.1:" + api.getAddress().getPort() + "/v1";

        String spec = OpenApiToMcpTest.OAS3.replace("https://{env}.pets.example/v1", apiBase).replace("\"variables\": {\"env\": {\"default\": \"api\"}}", "\"x\": 1");
        var gen = OpenApiToMcp.generate(spec, new OpenApiToMcp.Options(null, null, Set.of("GET", "POST", "DELETE"), null, null, null, null, false, 0, null));
        assertEquals(apiBase, gen.baseUrl());
        var exec = new OpenApiExecutor(new UrlGuard(true, Set.of()));
        var target = new OpenApiExecutor.Target(gen.baseUrl(), Map.of("Authorization", "Bearer api-secret"), Map.of(), 10_000, 1 << 20);
        core = new McpServerCore("pets", "1.0", "Pet store tools", gen.tools(), (tool, args) -> exec.call(tool, target, args));

        mcpHost = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        mcpHost.createContext("/mcp", ex -> {
            if (ex.getRequestMethod().equals("DELETE")) {
                mcpSeen.add("DELETE session=" + ex.getRequestHeaders().getFirst("Mcp-Session-Id"));
                reply(ex, 200, "text/plain", "");
                return;
            }
            String b = body(ex);
            mcpSeen.add("POST " + ex.getRequestHeaders().getFirst("Authorization") + " v=" + ex.getRequestHeaders().getFirst("MCP-Protocol-Version")
                    + " s=" + ex.getRequestHeaders().getFirst("Mcp-Session-Id") + " " + b);
            String out = core.handle(b);
            if (out == null) {
                reply(ex, 202, "text/plain", "");
            } else {
                String[] h = useSession && b.contains("\"initialize\"") ? new String[] {"Mcp-Session-Id", "sess-123"} : new String[0];
                if (sse) {
                    reply(ex, 200, "text/event-stream", ": keepalive\n\nevent: message\ndata: " + out + "\n\n", h);
                } else {
                    reply(ex, 200, "application/json", out, h);
                }
            }
        });
        mcpHost.start();
        mcpUrl = "http://127.0.0.1:" + mcpHost.getAddress().getPort() + "/mcp";
    }

    @AfterEach
    void stop() {
        api.stop(0);
        mcpHost.stop(0);
    }

    private final Mcp.Client client = new Mcp.Client(new UrlGuard(true, Set.of()));

    private Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("url", mcpUrl);
        m.put("headers", Map.of("Authorization", "Bearer mcp-token"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    @SuppressWarnings("unchecked")
    void aWorkflowNodeCallsAGeneratedToolAndMapsTheResult() throws Exception {
        Map<String, Object> out = client.call(cfg("tool", "getPet", "outputMap", Map.of("petName", "result.name", "ownerName", "result.owner.name", "missing", "result.nope")),
                Map.of("petId", "abc", "X-Trace", "t-1"));
        assertEquals("Rex", out.get("petName"));
        assertEquals("Ann", out.get("ownerName"));
        assertNull(out.get("missing"));
        assertEquals(7L, ((Number) ((Map<String, Object>) out.get("result")).get("id")).longValue());
        assertEquals(false, out.get("isError"));
        assertTrue(((String) out.get("text")).contains("Rex"));

        assertEquals("GET /v1/pets/abc auth=Bearer api-secret ct=null trace=t-1 body=", apiSeen.get(0),
                "the API sees the credential from the server config, not from the caller");
        assertTrue(mcpSeen.get(0).startsWith("POST Bearer mcp-token v=null s=null "), "headers reach the MCP server");
        assertTrue(mcpSeen.get(1).contains("notifications/initialized") && mcpSeen.get(1).contains("v=2025-06-18"), mcpSeen.get(1));
        assertEquals(3, mcpSeen.size());
    }

    @Test
    void toolsAreListedAndArrayResultsAreWrapped() throws Exception {
        Map<String, Object> list = client.call(cfg("operation", "listTools"), Map.of());
        List<?> tools = (List<?>) list.get("tools");
        assertEquals(List.of("listPets", "createPet", "getPet", "deletePet"),
                tools.stream().map(t -> ((Map<?, ?>) t).get("name")).toList());
        Map<?, ?> first = (Map<?, ?>) tools.get(0);
        assertEquals(true, ((Map<?, ?>) first.get("annotations")).get("readOnlyHint"));
        assertTrue(first.get("inputSchema") instanceof Map);

        Map<String, Object> out = client.call(cfg("tool", "listPets"), Map.of("limit", 5, "status", List.of("a", "b")));
        assertTrue(apiSeen.get(0).startsWith("GET /v1/pets?limit=5&status=a&status=b "), apiSeen.get(0));
        assertEquals(2, ((List<?>) ((Map<?, ?>) out.get("result")).get("result")).size());
    }

    @Test
    void bodiesAreSentAsJson() throws Exception {
        Map<String, Object> out = client.call(cfg("tool", "createPet"), Map.of("body", new java.util.LinkedHashMap<>(Map.of("name", "Fido"))));
        assertEquals("New", ((Map<?, ?>) out.get("result")).get("name"));
        assertTrue(apiSeen.get(0).contains("ct=application/json") && apiSeen.get(0).endsWith("body={\"name\":\"Fido\"}"), apiSeen.get(0));
    }

    @Test
    void badArgumentsAndApiErrorsComeBackAsToolErrorsNotCrashes() throws Exception {
        var e1 = assertThrows(ServiceHandler.ConnectorException.class, () -> client.call(cfg("tool", "getPet"), Map.of()));
        assertTrue(e1.getMessage().contains("petId is required"), e1.getMessage());
        var e2 = assertThrows(ServiceHandler.ConnectorException.class, () -> client.call(cfg("tool", "getPet"), Map.of("petId", "1", "admin", true)));
        assertTrue(e2.getMessage().contains("unknown argument 'admin'"), e2.getMessage());
        var e3 = assertThrows(ServiceHandler.ConnectorException.class, () -> client.call(cfg("tool", "listPets"), Map.of("limit", 1000)));
        assertTrue(e3.getMessage().contains("<= 100"), e3.getMessage());
        var e4 = assertThrows(ServiceHandler.ConnectorException.class, () -> client.call(cfg("tool", "getPet"), Map.of("petId", "404")));
        assertTrue(e4.getMessage().contains("HTTP 404") && e4.getMessage().contains("no such pet"), e4.getMessage());
        assertEquals(1, apiSeen.size(), "invalid calls never reach the API");

        Map<String, Object> soft = client.call(cfg("tool", "getPet", "failOnToolError", false), Map.of("petId", "404"));
        assertEquals(true, soft.get("isError"));
        var e5 = assertThrows(ServiceHandler.ConnectorException.class, () -> client.call(cfg("tool", "nope"), Map.of()));
        assertTrue(e5.getMessage().contains("-32602") && e5.getMessage().contains("Unknown tool"), e5.getMessage());
    }

    @Test
    void argumentsCannotEscapeTheirPathSegmentOrTheHost() throws Exception {
        client.call(cfg("tool", "getPet"), Map.of("petId", "../admin?x=1#frag"));
        String seen = apiSeen.get(0);
        assertTrue(seen.startsWith("GET /v1/pets/..%2Fadmin%3Fx%3D1%23frag "), seen);
        Map<String, Object> soft = client.call(cfg("tool", "getPet", "failOnToolError", false), Map.of("petId", ".."));
        assertEquals(true, soft.get("isError"));
        assertEquals(1, apiSeen.size(), "'..' is refused before any request");
        client.call(cfg("tool", "getPet"), Map.of("petId", "@evil.example/x"));
        assertTrue(apiSeen.get(1).startsWith("GET /v1/pets/%40evil.example%2Fx "), apiSeen.get(1));
    }

    @Test
    void serverSentEventResponsesAndSessionsAreHandled() throws Exception {
        sse = true;
        useSession = true;
        Map<String, Object> out = client.call(cfg("tool", "getPet"), Map.of("petId", "s"));
        assertEquals("Rex", ((Map<?, ?>) out.get("result")).get("name"));
        assertTrue(mcpSeen.get(1).contains("s=sess-123"), "the session id from initialize is sent back: " + mcpSeen.get(1));
        assertTrue(mcpSeen.stream().anyMatch(s -> s.equals("DELETE session=sess-123")), "and the session is closed: " + mcpSeen);
    }

    @Test
    void theServerSpeaksProperJsonRpc() {
        assertTrue(core.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"2025-03-26\"}}")
                .contains("\"protocolVersion\":\"2025-03-26\""), "negotiates a supported version");
        assertTrue(core.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"initialize\",\"params\":{\"protocolVersion\":\"1999-01-01\"}}")
                .contains("\"protocolVersion\":\"2025-06-18\""), "offers its own otherwise");
        assertNull(core.handle("{\"jsonrpc\":\"2.0\",\"method\":\"notifications/initialized\"}"));
        assertTrue(core.handle("{\"jsonrpc\":\"2.0\",\"id\":\"a\",\"method\":\"ping\"}").contains("\"id\":\"a\""));
        assertTrue(core.handle("{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"nope\"}").contains("-32601"));
        assertTrue(core.handle("not json").contains("-32700"));
        assertTrue(core.handle("{\"id\":1}").contains("-32600"));
        assertTrue(core.handle("{\"jsonrpc\":\"2.0\",\"id\":{},\"method\":\"ping\"}").contains("-32600"));
        assertTrue(core.handle("{\"jsonrpc\":\"2.0\",\"id\":3,\"method\":\"tools/call\",\"params\":{\"name\":\"getPet\",\"arguments\":[1]}}").contains("-32602"));
        assertTrue(core.handle("[]").contains("-32600"));
        String batch = core.handle("[{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"ping\"},{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"},{\"jsonrpc\":\"2.0\",\"id\":2,\"method\":\"ping\"}]");
        assertEquals(2, ((List<?>) Json.read(batch)).size());
        assertNull(core.handle("[{\"jsonrpc\":\"2.0\",\"method\":\"notifications/x\"}]"));
        assertTrue(core.handle("x".repeat((1 << 20) + 1)).contains("-32600"));
        String listed = core.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/list\"}");
        assertTrue(listed.contains("\"readOnlyHint\":true") && listed.contains("\"destructiveHint\":true"));
    }

    @Test
    void aBrokenInvokerBecomesAnErrorResultWithoutLeakingDetails() {
        var broken = new McpServerCore("x", "1", null, core == null ? List.of() : List.of(OpenApiToMcp.generate(OpenApiToMcpTest.OAS3, null).tools().get(1)),
                (t, a) -> {
                    throw new IllegalStateException("db password is hunter2");
                });
        String r = broken.handle("{\"jsonrpc\":\"2.0\",\"id\":1,\"method\":\"tools/call\",\"params\":{\"name\":\"getPet\",\"arguments\":{\"petId\":\"1\"}}}");
        assertTrue(r.contains("\"isError\":true") && r.contains("IllegalStateException") && !r.contains("hunter2"), r);
    }

    @Test
    void clientRefusesMisconfiguration() {
        assertThrows(ServiceHandler.ConnectorException.class, () -> client.call(Map.of("tool", "x"), Map.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> client.call(cfg(), Map.of()), "callTool needs a tool");
        assertThrows(ServiceHandler.ConnectorException.class, () -> client.call(cfg("tool", "x", "outputMap", Map.of("bad name", "a")), Map.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> client.call(cfg("operation", "dropTables"), Map.of()));
        var strict = new Mcp.Client(UrlGuard.strict());
        assertThrows(ServiceHandler.ConnectorException.class, () -> strict.call(cfg("tool", "getPet"), Map.of("petId", "1")), "loopback is blocked by the SSRF guard");
    }
}
