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

import tech.kayys.wayang.runtime.WebhookProvider;

/** Thumbs under a Slack reply: Block Kit buttons on request, and a press (interactivity payload) read as a feedback event. */
class SlackRatingsTest {

    private static final String TOKEN = "xo" + "xb-" + "fake-test-token-for-unit-tests";

    private HttpServer server;
    private final List<String> bodies = new CopyOnWriteArrayList<>();
    private Slack.Sender sender;

    @BeforeEach
    void start() throws Exception {
        sender = new Slack.Sender(new UrlGuard(true, Set.of()), new MediaStore(Files.createTempDirectory("slrate")));
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", ex -> {
            ByteArrayOutputStream in = new ByteArrayOutputStream();
            ex.getRequestBody().transferTo(in);
            bodies.add(in.toString(StandardCharsets.UTF_8));
            byte[] r = "{\"ok\":true,\"ts\":\"1.2\",\"channel\":\"C0123456789\"}".getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, r.length);
            ex.getResponseBody().write(r);
            ex.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private Map<String, Object> cfg() {
        return Map.of("botToken", TOKEN, "baseUrl", "http://127.0.0.1:" + server.getAddress().getPort());
    }

    @Test
    void theButtonsAreAddedOnlyWhenAsked() throws Exception {
        sender.call(cfg(), Map.of("channel", "C0123456789", "text", "plain"));
        sender.call(cfg(), Map.of("channel", "C0123456789", "text", "rated", "rate", true));
        assertFalse(bodies.get(0).contains("blocks"));
        String rated = bodies.get(1);
        assertTrue(rated.contains("\"blocks\"") && rated.contains("fb_up") && rated.contains("fb_down") && rated.contains("\"text\":\"rated\""), rated);
    }

    @Test
    void aLongReplyIsSplitIntoSectionsOfAtMost3000Characters() throws Exception {
        sender.call(cfg(), Map.of("channel", "C0123456789", "text", "x".repeat(7000), "rate", "true"));
        String b = bodies.get(0);
        assertEquals(3, b.split("\"type\":\"section\"", -1).length - 1, b.length() + " chars");
        @SuppressWarnings("unchecked")
        Map<String, Object> sent = (Map<String, Object>) tech.kayys.wayang.runtime.json.Json.read(b);
        for (Object blk : (List<?>) sent.get("blocks")) {
            Map<?, ?> m = (Map<?, ?>) blk;
            if ("section".equals(m.get("type"))) {
                assertTrue(((String) ((Map<?, ?>) m.get("text")).get("text")).length() <= 3000);
            }
        }
        assertEquals(7000, ((String) sent.get("text")).length(), "the notification text stays whole");
    }

    private static Map<String, Object> press(String channel, String user, String actionId) {
        Map<String, Object> action = new LinkedHashMap<>(Map.of("action_id", actionId, "action_ts", "9.9"));
        return Map.of("type", "block_actions", "user", Map.of("id", user), "channel", Map.of("id", channel),
                "message", Map.of("ts", "5.5", "text", "The reply"), "actions", List.of(action));
    }

    private final Slack.Webhook hook = new Slack.Webhook();

    @Test
    void aPressOnOurButtonBecomesAFeedbackEvent() {
        var cfg = Map.<String, Object>of("allowedChannels", List.of("C1"), "ratings", true);
        List<WebhookProvider.Event> ev = hook.events(cfg, press("C1", "U7", "fb_up"));
        assertEquals(1, ev.size());
        assertEquals("feedback", ev.get(0).data().get("kind"));
        assertEquals("up", ev.get(0).data().get("rating"));
        assertEquals("The reply", ev.get(0).data().get("reply"));
        assertEquals("5.5", ev.get(0).data().get("messageTs"));
        assertEquals(ev.get(0).id(), hook.events(cfg, press("C1", "U7", "fb_up")).get(0).id(), "a retry has the same id");
    }

    @Test
    void otherButtonsChannelsPeopleAndMissingRatingsAreIgnored() {
        var on = Map.<String, Object>of("allowedChannels", List.of("C1"), "allowedUsers", List.of("U7"), "ratings", true);
        assertTrue(hook.events(on, press("C1", "U7", "other")).isEmpty());
        assertTrue(hook.events(on, press("C2", "U7", "fb_up")).isEmpty());
        assertTrue(hook.events(on, press("C1", "U8", "fb_up")).isEmpty());
        assertTrue(hook.events(Map.of("allowedChannels", List.of("C1")), press("C1", "U7", "fb_up")).isEmpty(), "ratings is off");
        assertTrue(hook.events(Map.of("ratings", true), press("C1", "U7", "fb_up")).isEmpty(), "no allowed channel");
    }

    @Test
    void theRatedReplyIsCutToAnExcerpt() {
        Map<String, Object> p = new LinkedHashMap<>(press("C1", "U7", "fb_up"));
        p.put("message", Map.of("ts", "5.5", "text", "y".repeat(900)));
        var ev = hook.events(Map.of("allowedChannels", List.of("C1"), "ratings", true), p);
        assertEquals(Slack.RATED_EXCERPT, ((String) ev.get(0).data().get("reply")).length());
    }
}
