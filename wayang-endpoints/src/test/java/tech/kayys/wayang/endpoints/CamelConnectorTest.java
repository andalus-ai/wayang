package tech.kayys.wayang.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;

class CamelConnectorTest {

    private static HttpServer server(AtomicReference<String> seen, int status, String reply) throws Exception {
        HttpServer s = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        s.createContext("/", ex -> {
            seen.set(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + ex.getRequestHeaders().getFirst("X-Order") + " "
                    + ex.getRequestHeaders().getFirst("Authorization") + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = reply.getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        s.start();
        return s;
    }

    private static String base(HttpServer s) {
        return "http://127.0.0.1:" + s.getAddress().getPort() + "/";
    }

    @Test
    void postsTheMessageToTheRouteAndReturnsTheAnswer() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer s = server(seen, 200, "{\"total\":5}");
        try {
            var c = new CamelConnector(new UrlGuard(true, Set.of()));
            var out = c.call(Map.of("baseUrl", base(s), "route", "/orders/enrich", "headers", Map.of("Authorization", "Bearer t")),
                    Map.of("body", Map.of("id", 1), "headers", Map.of("X-Order", "7")));
            assertEquals(200, out.get("status"));
            assertEquals(5, ((Number) ((Map<?, ?>) out.get("body")).get("total")).intValue());
            assertEquals("POST /orders/enrich 7 Bearer t {\"id\":1}", seen.get());
        } finally {
            s.stop(0);
        }
    }

    @Test
    void aRoutePathCannotEscapeTheBaseUrl() {
        var c = new CamelConnector(new UrlGuard(true, Set.of()));
        for (String bad : new String[] {"../admin", "a/../b", "a?x=1", "a b", "http://evil/x", "a//b"}) {
            var e = assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("baseUrl", "http://127.0.0.1:1/", "route", bad), Map.of()), bad);
            assertTrue(e.getMessage().contains("route"), bad);
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of(), Map.of()), "no baseUrl");
    }

    @Test
    void thePrivateAddressGuardApplies() {
        var c = new CamelConnector(UrlGuard.strict());
        assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("baseUrl", "http://127.0.0.1:9/", "route", "x"), Map.of()));
    }

    @Test
    void aFailingRouteIsRetryableOnlyFor5xx() throws Exception {
        AtomicReference<String> seen = new AtomicReference<>();
        HttpServer s500 = server(seen, 500, "{}");
        HttpServer s400 = server(seen, 400, "{}");
        try {
            var c = new CamelConnector(new UrlGuard(true, Set.of()));
            var e5 = assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("baseUrl", base(s500), "route", "r"), Map.of()));
            var e4 = assertThrows(ServiceHandler.ConnectorException.class, () -> c.call(Map.of("baseUrl", base(s400), "route", "r"), Map.of()));
            assertTrue(e5.retryable());
            assertTrue(!e4.retryable());
        } finally {
            s500.stop(0);
            s400.stop(0);
        }
    }
}
