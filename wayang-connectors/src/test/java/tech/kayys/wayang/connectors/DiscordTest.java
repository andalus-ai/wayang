package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.ServiceHandler;

class DiscordTest {
    private HttpServer server;
    private final List<String> requests = new CopyOnWriteArrayList<>();
    private volatile int status = 200;
    private static final String TOKEN = "abcdefghijklmnopqrstuvwxyz0123456789";

    @BeforeEach
    void up() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            requests.add(ex.getRequestMethod() + " " + ex.getRequestURI() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            byte[] b = (status == 200 ? "{\"id\":\"99\",\"channel_id\":\"55\"}" : "{\"message\":\"nope\"}").getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(status, b.length);
            ex.getResponseBody().write(b);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void down() {
        server.stop(0);
    }

    private String hook() {
        return "http://127.0.0.1:" + server.getAddress().getPort() + "/api/webhooks/123456789/" + TOKEN;
    }

    private final UrlGuard local = new UrlGuard(true, Set.of());

    @Test
    void postsWithMentionsSuppressed() throws Exception {
        Map<String, Object> out = new Discord.Sender(local, true).call(Map.of("webhookUrl", hook()),
                Map.of("text", "hi @everyone", "username", "Wayang", "threadId", "4242424242"));
        assertEquals("99", out.get("messageId"));
        String r = requests.get(0);
        assertTrue(r.startsWith("POST /api/webhooks/123456789/" + TOKEN + "?wait=true&thread_id=4242424242"), r);
        assertTrue(r.contains("\"allowed_mentions\":{\"parse\":[]}"), r);
        assertTrue(r.contains("\"username\":\"Wayang\""));
    }

    @Test
    void onlyOfficialDiscordUrlsAreAcceptedByDefault() {
        var s = new Discord.Sender(local);
        for (String bad : List.of(hook(), "https://evil.example/api/webhooks/123456789/" + TOKEN,
                "https://discord.com.evil.example/api/webhooks/123456789/" + TOKEN,
                "https://user@discord.com/api/webhooks/123456789/" + TOKEN, "https://discord.com/api/other", "not a url")) {
            var e = assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(Map.of("webhookUrl", bad), Map.of("text", "x")), bad);
            assertFalse(e.getMessage().contains(TOKEN), "the token must never be echoed");
        }
        assertEquals(0, requests.size());
    }

    @Test
    void validatesInputsAndMapsHttpErrors() throws Exception {
        var s = new Discord.Sender(local, true);
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(Map.of("webhookUrl", hook()), Map.of("text", " ")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(Map.of("webhookUrl", hook()), Map.of("text", "x".repeat(2001))));
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(Map.of("webhookUrl", hook()), Map.of("text", "x", "threadId", "1&evil=1")));
        status = 429;
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(Map.of("webhookUrl", hook()), Map.of("text", "x")));
        assertTrue(e.retryable());
        assertFalse(e.getMessage().contains(TOKEN));
    }
}
