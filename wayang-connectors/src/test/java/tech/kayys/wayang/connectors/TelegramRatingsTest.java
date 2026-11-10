package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayOutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.Poller;

/** Thumbs under a reply: sendMessage adds the buttons on request, and with ratings:true the poller turns a press into a feedback event. */
class TelegramRatingsTest {

    private static final String TOKEN = "123456789:ABCdefGhIJKlmNoPQRsTUVwxyZ0123456789";

    private HttpServer server;
    private final List<String> calls = new CopyOnWriteArrayList<>();
    private volatile String updates = "[]";
    private volatile boolean answerFails;
    private Telegram.Sender sender;

    @BeforeEach
    void start() throws Exception {
        sender = new Telegram.Sender(new UrlGuard(true, Set.of()), new MediaStore(Files.createTempDirectory("tgrate")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            String p = ex.getRequestURI().getPath();
            ByteArrayOutputStream in = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(in);
            calls.add(p.substring(p.lastIndexOf('/') + 1) + " " + ex.getRequestURI().getQuery() + " " + in.toString(StandardCharsets.UTF_8));
            ex.getResponseHeaders().add("Content-Type", "application/json");
            String body;
            int status = 200;
            if (p.endsWith("/answerCallbackQuery") && answerFails) {
                status = 500;
                body = "{\"ok\":false}";
            } else if (p.endsWith("/getUpdates")) {
                body = "{\"ok\":true,\"result\":" + updates + "}";
            } else {
                body = "{\"ok\":true,\"result\":{\"message_id\":9,\"chat\":{\"id\":42}}}";
            }
            byte[] r = body.getBytes(StandardCharsets.UTF_8);
            ex.sendResponseHeaders(status, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("botToken", TOKEN);
        m.put("baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String press(int updateId, long chatId, long fromId, String data) {
        return "{\"update_id\":" + updateId + ",\"callback_query\":{\"id\":\"q" + updateId + "\",\"from\":{\"id\":" + fromId + "},\"data\":\"" + data
                + "\",\"message\":{\"message_id\":9,\"chat\":{\"id\":" + chatId + ",\"type\":\"private\"},\"text\":\"The reply\"}}}";
    }

    @Test
    void theButtonsAreAddedOnlyWhenAsked() throws Exception {
        sender.call(cfg(), Map.of("chatId", "42", "text", "plain"));
        sender.call(cfg(), Map.of("chatId", "42", "text", "rated", "rate", true));
        sender.call(cfg(), Map.of("chatId", "42", "text", "no", "rate", "false"));
        List<String> sends = calls.stream().filter(c -> c.startsWith("sendMessage")).toList();
        assertEquals(3, sends.size());
        assertFalse(sends.get(0).contains("reply_markup"));
        assertTrue(sends.get(1).contains("inline_keyboard") && sends.get(1).contains("fb:up") && sends.get(1).contains("fb:down"), sends.get(1));
        assertFalse(sends.get(2).contains("reply_markup"));
    }

    @Test
    void aPressBecomesAFeedbackEventAndIsAcknowledged() throws Exception {
        updates = "[" + press(1, 42, 7, "fb:down") + "]";
        Poller.Result r = new Telegram.Updates(new UrlGuard(true, Set.of())).poll(cfg("allowedChatIds", List.of(42), "ratings", true), null);
        assertEquals(1, r.events().size(), r.events().toString());
        Map<String, Object> e = r.events().get(0);
        assertEquals("feedback", e.get("kind"));
        assertEquals("down", e.get("rating"));
        assertEquals("The reply", e.get("reply"));
        assertEquals(9, ((Number) e.get("messageId")).intValue());
        assertEquals("2", r.nextCursor());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("answerCallbackQuery") && c.contains("\"callback_query_id\":\"q1\"")), calls.toString());
        assertTrue(calls.stream().anyMatch(c -> c.startsWith("getUpdates") && c.contains("callback_query")));
        assertFalse(e.toString().contains(TOKEN));
    }

    @Test
    void withoutRatingsPressesAreNeitherRequestedNorEmitted() throws Exception {
        updates = "[" + press(1, 42, 7, "fb:up") + "]";
        Poller.Result r = new Telegram.Updates(new UrlGuard(true, Set.of())).poll(cfg("allowedChatIds", List.of(42)), null);
        assertTrue(r.events().isEmpty());
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("getUpdates") && c.contains("callback_query")));
        assertFalse(calls.stream().anyMatch(c -> c.startsWith("answerCallbackQuery")));
    }

    @Test
    void onlyAllowedChatsPeopleAndOurOwnButtonsCount() throws Exception {
        updates = "[" + press(1, 99, 7, "fb:up") + "," + press(2, 42, 8, "fb:up") + "," + press(3, 42, 7, "other:thing") + "," + press(4, 42, 7, "fb:up") + "]";
        Poller.Result r = new Telegram.Updates(new UrlGuard(true, Set.of()))
                .poll(cfg("allowedChatIds", List.of(42), "allowedUserIds", List.of(7), "ratings", true), null);
        assertEquals(1, r.events().size(), r.events().toString());
        assertEquals(4L, ((Number) r.events().get(0).get("updateId")).longValue());
        assertEquals("5", r.nextCursor(), "every update is consumed, accepted or not");
        assertEquals(1L, calls.stream().filter(c -> c.startsWith("answerCallbackQuery")).count(), "only an accepted press is answered");
    }

    @Test
    void aFailedAcknowledgementStillRecordsTheRating() throws Exception {
        answerFails = true;
        updates = "[" + press(1, 42, 7, "fb:up") + "]";
        Poller.Result r = new Telegram.Updates(new UrlGuard(true, Set.of())).poll(cfg("allowedChatIds", List.of(42), "ratings", true), null);
        assertEquals("up", r.events().get(0).get("rating"));
    }

    @Test
    void theRatedReplyIsCutToAnExcerpt() throws Exception {
        updates = "[" + press(1, 42, 7, "fb:up").replace("The reply", "x".repeat(900)) + "]";
        Poller.Result r = new Telegram.Updates(new UrlGuard(true, Set.of())).poll(cfg("allowedChatIds", List.of(42), "ratings", true), null);
        assertEquals(Telegram.RATED_EXCERPT, ((String) r.events().get(0).get("reply")).length());
    }
}
