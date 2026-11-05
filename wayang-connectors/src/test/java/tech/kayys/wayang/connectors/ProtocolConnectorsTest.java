package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

class ProtocolConnectorsTest {

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile String reply = "{}";
    private volatile int replyStatus = 200;
    private final UrlGuard guard = new UrlGuard(true, Set.of());

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            seen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + body + " | " + ex.getRequestHeaders().getFirst("Authorization")
                    + " | " + ex.getRequestHeaders().getFirst("Connect-Protocol-Version"));
            byte[] r = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", reply.startsWith("<") ? "text/html" : "application/json");
            ex.sendResponseHeaders(replyStatus, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static Map<String, Object> m(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void allFourAreRegisteredByDefault() {
        var c = Connectors.defaults(guard);
        assertTrue(c.keySet().containsAll(Set.of("graphql", "a2a", "grpc", "websocket", "mcp", "soap", "http")), c.keySet().toString());
    }

    // ---- graphql
    @Test
    void graphqlSendsTheFixedQueryWithInputsAsVariablesAndReadsData() throws Exception {
        reply = "{\"data\":{\"user\":{\"name\":\"Budi\"}}}";
        var out = new GraphQl.Client(guard).call(m("url", base() + "/graphql", "headers", m("Authorization", "Bearer t"),
                "query", "query($id: ID!){user(id:$id){name}}", "outputMap", m("who", "data.user.name")), m("id", "7"));
        assertEquals("Budi", out.get("who"));
        String s = seen.get(0);
        assertTrue(s.startsWith("POST /graphql ") && s.contains("\"variables\":{\"id\":\"7\"}") && s.contains("query($id: ID!)") && s.endsWith("Bearer t | null"), s);
    }

    @Test
    void graphqlErrorsFailUnlessAskedNotToAndSubscriptionsAreRefused() throws Exception {
        reply = "{\"data\":null,\"errors\":[{\"message\":\"not allowed\"},{\"message\":\"x\"}]}";
        Map<String, Object> cfg = m("url", base() + "/g", "query", "{ me { id } }");
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new GraphQl.Client(guard).call(cfg, Map.of()));
        assertTrue(e.getMessage().contains("not allowed") && e.getMessage().contains("+1 more"), e.getMessage());
        var out = new GraphQl.Client(guard).call(m("url", base() + "/g", "query", "{ me { id } }", "failOnErrors", false), Map.of());
        assertEquals(2, ((List<?>) out.get("errors")).size());
        for (String q : new String[] {"subscription { a }", "# c\n  subscription S { a }"}) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> new GraphQl.Client(guard).call(m("url", base(), "query", q), Map.of()));
        }
        int before = seen.size();
        assertThrows(ServiceHandler.ConnectorException.class, () -> new GraphQl.Client(guard).call(m("url", base()), Map.of()));
        assertEquals(before, seen.size());
    }

    @Test
    void graphqlQueryFromInputsOnlyWhenAllowed() throws Exception {
        reply = "{\"data\":{\"ok\":true}}";
        new GraphQl.Client(guard).call(m("url", base(), "query", "{ fixed }"), m("query", "{ injected }"));
        assertTrue(seen.get(0).contains("{ fixed }") && seen.get(0).contains("\"variables\":{\"query\":\"{ injected }\"}"), seen.get(0));
        new GraphQl.Client(guard).call(m("url", base(), "query", "{ fixed }", "allowDynamicQuery", true), m("query", "{ dynamic }", "variables", m("a", 1)));
        assertTrue(seen.get(1).contains("{ dynamic }") && seen.get(1).contains("\"a\":1"), seen.get(1));
    }

    @Test
    void graphqlWithoutAGraphqlAnswerIsAnErrorWithRetryOnOverload() {
        reply = "<html>bad gateway</html>";
        replyStatus = 502;
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new GraphQl.Client(guard).call(m("url", base(), "query", "{a}"), Map.of()));
        assertTrue(e.retryable() && e.getMessage().contains("502"), e.getMessage());
    }

    // ---- grpc (connect, json)
    @Test
    void grpcPostsJsonToServiceAndMethodWithTheConnectHeader() throws Exception {
        reply = "{\"greeting\":\"hi Budi\"}";
        var out = new Grpc.Client(guard).call(m("url", base() + "/", "service", "acme.v1.Greeter", "method", "SayHello", "outputMap", m("g", "greeting")), m("name", "Budi"));
        assertEquals("hi Budi", out.get("g"));
        assertEquals("hi Budi", ((Map<?, ?>) out.get("response")).get("greeting"));
        assertEquals("POST /acme.v1.Greeter/SayHello {\"name\":\"Budi\"} | null | 1", seen.get(0));
    }

    @Test
    void grpcErrorsCarryTheCodeAndOnlyTransientOnesAreRetried() {
        replyStatus = 503;
        reply = "{\"code\":\"unavailable\",\"message\":\"try later\"}";
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new Grpc.Client(guard).call(m("url", base(), "service", "a.B", "method", "C"), Map.of()));
        assertTrue(e.retryable() && e.getMessage().contains("unavailable") && e.getMessage().contains("try later"), e.getMessage());
        replyStatus = 404;
        reply = "{\"code\":\"not_found\",\"message\":\"no such user\"}";
        var n = assertThrows(ServiceHandler.ConnectorException.class, () -> new Grpc.Client(guard).call(m("url", base(), "service", "a.B", "method", "C"), Map.of()));
        assertFalse(n.retryable());
    }

    @Test
    void grpcServiceAndMethodAreValidatedBeforeAnyRequest() {
        for (String[] bad : new String[][] {{"a/../b", "C"}, {"a.B", "C/D"}, {"", "C"}, {"a.B", ""}, {"a.B?x=1", "C"}}) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> new Grpc.Client(guard).call(m("url", base(), "service", bad[0], "method", bad[1]), Map.of()));
        }
        assertTrue(seen.isEmpty(), seen.toString());
    }

    // ---- a2a
    @Test
    void a2aSendReturnsTheAgentsArtifactTextAndIds() throws Exception {
        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"task\",\"id\":\"t-1\",\"contextId\":\"c-1\",\"status\":{\"state\":\"completed\","
                + "\"message\":{\"parts\":[{\"kind\":\"text\",\"text\":\"done\"}]}},\"artifacts\":[{\"parts\":[{\"kind\":\"text\",\"text\":\"The answer is 42\"}]}]}}";
        var out = new A2a.Client(guard).call(m("url", base() + "/a2a", "headers", m("Authorization", "Bearer k")), m("text", "what is 6x7?"));
        assertEquals("The answer is 42", out.get("text"));
        assertEquals("completed", out.get("state"));
        assertEquals("t-1", out.get("taskId"));
        assertEquals("c-1", out.get("contextId"));
        String s = seen.get(0);
        assertTrue(s.contains("\"method\":\"message/send\"") && s.contains("\"text\":\"what is 6x7?\"") && s.contains("\"role\":\"user\"")
                && s.contains("\"blocking\":true") && s.endsWith("Bearer k | null"), s);
    }

    @Test
    void a2aDirectMessageReplyAndFailedTasksAndRpcErrors() throws Exception {
        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"message\",\"role\":\"agent\",\"parts\":[{\"kind\":\"text\",\"text\":\"hello\"}]}}";
        assertEquals("hello", new A2a.Client(guard).call(m("url", base()), m("text", "hi")).get("text"));
        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"task\",\"id\":\"t\",\"status\":{\"state\":\"failed\",\"message\":{\"parts\":[{\"kind\":\"text\",\"text\":\"boom\"}]}}}}";
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new A2a.Client(guard).call(m("url", base()), m("text", "hi")));
        assertTrue(e.getMessage().contains("failed") && e.getMessage().contains("boom"), e.getMessage());
        assertEquals("failed", new A2a.Client(guard).call(m("url", base(), "failOnTaskFailure", false), m("text", "hi")).get("state"));
        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"error\":{\"code\":-32601,\"message\":\"Method not found\"}}";
        var r = assertThrows(ServiceHandler.ConnectorException.class, () -> new A2a.Client(guard).call(m("url", base()), m("text", "hi")));
        assertTrue(r.getMessage().contains("-32601") && r.getMessage().contains("Method not found"), r.getMessage());
    }

    @Test
    void a2aGetTaskAndInputChecksAndAgentCard() throws Exception {
        reply = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"result\":{\"kind\":\"task\",\"id\":\"t-9\",\"status\":{\"state\":\"working\"}}}";
        var out = new A2a.Client(guard).call(m("url", base(), "operation", "getTask"), m("taskId", "t-9"));
        assertEquals("working", out.get("state"));
        assertTrue(seen.get(0).contains("\"method\":\"tasks/get\"") && seen.get(0).contains("\"id\":\"t-9\""), seen.get(0));
        int n = seen.size();
        assertThrows(ServiceHandler.ConnectorException.class, () -> new A2a.Client(guard).call(m("url", base(), "operation", "getTask"), m("taskId", "../x")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> new A2a.Client(guard).call(m("url", base()), m("text", " ")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> new A2a.Client(guard).call(m("url", base(), "operation", "nope"), m("text", "x")));
        assertEquals(n, seen.size());
        reply = "{\"name\":\"Helper\",\"description\":\"does things\",\"skills\":[{\"id\":\"s\",\"name\":\"Math\"}]}";
        var card = new A2a.Client(guard).call(m("url", base() + "/rpc", "operation", "agentCard"), Map.of());
        assertEquals("Helper", card.get("name"));
        assertEquals(List.of("Math"), card.get("skills"));
        assertTrue(seen.get(seen.size() - 1).startsWith("GET /.well-known/agent-card.json"), seen.get(seen.size() - 1));
    }

    // ---- websocket (a minimal server: RFC 6455 handshake, one request frame, N answer frames)
    private ServerSocket wsServer(List<String> answers, List<String> received) throws Exception {
        ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress());
        Thread t = new Thread(() -> {
            try (Socket s = ss.accept()) {
                InputStream in = s.getInputStream();
                OutputStream out = s.getOutputStream();
                StringBuilder head = new StringBuilder();
                while (!head.toString().endsWith("\r\n\r\n")) {
                    head.append((char) in.read());
                }
                received.add(head.toString());
                String key = head.toString().lines().filter(l -> l.toLowerCase().startsWith("sec-websocket-key:")).findFirst().orElseThrow().substring(18).strip();
                String acc = Base64.getEncoder().encodeToString(MessageDigest.getInstance("SHA-1").digest((key + "258EAFA5-E914-47DA-95CA-C5AB0DC85B11").getBytes(StandardCharsets.US_ASCII)));
                out.write(("HTTP/1.1 101 Switching Protocols\r\nUpgrade: websocket\r\nConnection: Upgrade\r\nSec-WebSocket-Accept: " + acc + "\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                out.flush();
                int b0 = in.read(); // client text frame (masked)
                int len = in.read() & 0x7F;
                byte[] mask = in.readNBytes(4);
                byte[] pl = in.readNBytes(len);
                for (int i = 0; i < pl.length; i++) {
                    pl[i] ^= mask[i % 4];
                }
                received.add(new String(pl, StandardCharsets.UTF_8));
                for (String a : answers) {
                    byte[] p = a.getBytes(StandardCharsets.UTF_8);
                    out.write(0x81);
                    out.write(p.length);
                    out.write(p);
                }
                out.flush();
                Thread.sleep(300);
            } catch (Exception ignored) {
                // test server: the client side asserts
            }
        });
        t.setDaemon(true);
        t.start();
        return ss;
    }

    @Test
    void websocketSendsOneMessageAndCollectsTheAnswers() throws Exception {
        List<String> got = new CopyOnWriteArrayList<>();
        try (ServerSocket ss = wsServer(List.of("{\"n\":1}", "two"), got)) {
            var out = new WebSocketClient(guard).call(m("url", "ws://127.0.0.1:" + ss.getLocalPort() + "/feed", "collect", 2, "waitMs", 5000,
                    "headers", m("Authorization", "Bearer w"), "outputMap", m("n", "json.n")), m("message", m("op", "hello")));
            assertEquals(List.of("{\"n\":1}", "two"), out.get("messages"));
            assertEquals("two", out.get("text"));
            assertEquals(Boolean.FALSE, out.get("timedOut"));
            assertTrue(got.get(0).startsWith("GET /feed ") && got.get(0).contains("Authorization: Bearer w"), got.get(0));
            assertEquals("{\"op\":\"hello\"}", got.get(1));
        }
    }

    @Test
    void websocketRefusesBadUrlsHeadersAndLimitsBeforeConnecting() {
        for (Map<String, Object> cfg : List.of(m("url", "http://127.0.0.1:1/"), m("url", "ws://127.0.0.1:1/", "collect", 99),
                m("url", "ws://127.0.0.1:1/", "headers", m("Upgrade", "x")), m("url", "ws://127.0.0.1:1/", "subprotocol", "a b"),
                m("url", "ws://user:pw@127.0.0.1:1/"))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> new WebSocketClient(guard).call(cfg, Map.of()), cfg.toString());
        }
        // private targets are refused unless the operator allows them
        assertThrows(ServiceHandler.ConnectorException.class, () -> new WebSocketClient(UrlGuard.strict()).call(m("url", "ws://127.0.0.1:1/"), Map.of()));
    }

    @Test
    void websocketConnectionFailureIsRetryableAndDoesNotEchoTheUrl() throws Exception {
        int port;
        try (ServerSocket ss = new ServerSocket(0, 1, InetAddress.getLoopbackAddress())) {
            port = ss.getLocalPort();
        }
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new WebSocketClient(guard).call(m("url", "ws://127.0.0.1:" + port + "/secret-path"), Map.of()));
        assertTrue(e.retryable());
        assertFalse(e.getMessage().contains("secret-path"));
    }
}
