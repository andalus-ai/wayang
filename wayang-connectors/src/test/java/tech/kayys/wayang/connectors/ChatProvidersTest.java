package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WebhookProvider;

class ChatProvidersTest {

    private final Slack.Webhook slack = new Slack.Webhook();
    private final WhatsApp.Webhook wa = new WhatsApp.Webhook();

    // Example from Slack's "Verifying requests from Slack" documentation (independently reproduced with openssl).
    private static final String SLACK_SECRET = "8f742231b10e8888abcd99yyyzzz85a5";
    private static final String SLACK_TS = "1531420618";
    private static final String SLACK_BODY = "token=xyzz0WbapA4vBCDEFasx0q6G&team_id=T1DC2JH3J&team_domain=testteamnow&channel_id=G8PSS9T3V"
            + "&channel_name=foobar&user_id=U2CERLKJA&user_name=roadrunner&command=%2Fwebhook-collect&text="
            + "&response_url=https%3A%2F%2Fhooks.slack.com%2Fcommands%2FT1DC2JH3J%2F397700885554%2F96rGlfmibIGlgcZRskXaIFfN"
            + "&trigger_id=398738663015.47445629121.803a0bc887a14d10d2c447fce8b6703c";
    private static final String SLACK_SIG = "v0=a2114d57b48eac39b9ad189dd8316235a7b4a8d21a10bd27519666489c69b503";

    private static byte[] b(String s) {
        return s.getBytes(StandardCharsets.UTF_8);
    }

    private void assertRejected(WebhookProvider p, Map<String, Object> cfg, Map<String, String> h, String body, Instant now) {
        assertThrows(WebhookProvider.Rejected.class, () -> p.verify(cfg, h, b(body), now));
    }

    // ----------------------------------------------------------------- slack

    @Test
    void slackVerifiesTheDocumentedExampleAndRefusesAnyTampering() throws Exception {
        Map<String, Object> cfg = Map.of("signingSecret", SLACK_SECRET);
        Instant now = Instant.ofEpochSecond(1531420618L + 30);
        Map<String, String> good = Map.of("x-slack-request-timestamp", SLACK_TS, "x-slack-signature", SLACK_SIG);
        slack.verify(cfg, good, b(SLACK_BODY), now);

        assertRejected(slack, cfg, good, SLACK_BODY + "x", now);
        assertRejected(slack, Map.of("signingSecret", "other"), good, SLACK_BODY, now);
        assertRejected(slack, cfg, Map.of("x-slack-request-timestamp", SLACK_TS), SLACK_BODY, now);
        assertRejected(slack, cfg, Map.of("x-slack-request-timestamp", "abc", "x-slack-signature", SLACK_SIG), SLACK_BODY, now);
        assertRejected(slack, Map.of(), good, SLACK_BODY, now);
        // right signature, but replayed 6 minutes later, or claiming to come from the future
        assertRejected(slack, cfg, good, SLACK_BODY, Instant.ofEpochSecond(1531420618L + 360));
        assertRejected(slack, cfg, good, SLACK_BODY, Instant.ofEpochSecond(1531420618L - 360));
    }

    @Test
    void slackAnswersTheUrlVerificationChallenge() {
        assertEquals("abc123", slack.handshake(Map.of(), Map.of(), Map.of("type", "url_verification", "challenge", "abc123")).orElseThrow());
        assertTrue(slack.handshake(Map.of(), Map.of(), Map.of("type", "event_callback")).isEmpty());
    }

    private static Map<String, Object> slackEvent(Map<String, Object> inner) {
        return Map.of("type", "event_callback", "event_id", "Ev1", "event", inner);
    }

    @Test
    void slackEventsAreFilteredByChannelBotsAndSubtypes() {
        Map<String, Object> cfg = Map.of("allowedChannels", List.of("D1"));
        var ok = slack.events(cfg, slackEvent(Map.of("type", "message", "channel", "D1", "user", "U1", "text", "hello", "ts", "1.1")));
        assertEquals(1, ok.size());
        assertEquals("Ev1", ok.get(0).id());
        assertEquals("hello", ok.get(0).data().get("text"));
        assertEquals("D1", ok.get(0).data().get("channel"));

        assertTrue(slack.events(cfg, slackEvent(Map.of("type", "message", "channel", "C9", "text", "x", "ts", "1"))).isEmpty(), "other channel");
        assertTrue(slack.events(cfg, slackEvent(Map.of("type", "message", "channel", "D1", "bot_id", "B1", "text", "x", "ts", "1"))).isEmpty(), "bots (loop guard)");
        assertTrue(slack.events(cfg, slackEvent(Map.of("type", "message", "channel", "D1", "subtype", "message_changed", "text", "x", "ts", "1"))).isEmpty(), "edits");
        assertTrue(slack.events(cfg, slackEvent(Map.of("type", "reaction_added", "channel", "D1"))).isEmpty(), "other event types");
        assertTrue(slack.events(Map.of(), slackEvent(Map.of("type", "message", "channel", "D1", "text", "x", "ts", "1"))).isEmpty(), "no allow-list means nothing");
        assertEquals(1, slack.events(cfg, slackEvent(Map.of("type", "app_mention", "channel", "D1", "user", "U", "text", "<@B> hi", "ts", "2"))).size());
    }

    @Test
    void slackAllowedUsersNarrowTheChannel() {
        Map<String, Object> cfg = Map.of("allowedChannels", List.of("C1"), "allowedUsers", List.of("U1"));
        assertEquals(1, slack.events(cfg, slackEvent(Map.of("type", "message", "channel", "C1", "user", "U1", "text", "hi", "ts", "1"))).size());
        assertTrue(slack.events(cfg, slackEvent(Map.of("type", "message", "channel", "C1", "user", "U2", "text", "hi", "ts", "1"))).isEmpty(), "another member");
        assertTrue(slack.events(cfg, slackEvent(Map.of("type", "message", "channel", "C1", "text", "hi", "ts", "1"))).isEmpty(), "no sender");
        Map<String, Object> open = Map.of("allowedChannels", List.of("C1"), "allowedUsers", List.of());
        assertEquals(1, slack.events(open, slackEvent(Map.of("type", "message", "channel", "C1", "user", "U2", "text", "hi", "ts", "1"))).size(), "empty list = the channel rule only");
    }

    // -------------------------------------------------------------- whatsapp

    private static String sign(String secret, String body) {
        return "sha256=" + Hmac.sha256Hex(secret, b(body));
    }

    @Test
    void whatsappVerifiesTheBodySignature() throws Exception {
        Map<String, Object> cfg = Map.of("appSecret", "app-secret");
        String body = "{\"object\":\"whatsapp_business_account\"}";
        wa.verify(cfg, Map.of("x-hub-signature-256", sign("app-secret", body)), b(body), Instant.now());
        assertRejected(wa, cfg, Map.of("x-hub-signature-256", sign("app-secret", body)), body + " ", Instant.now());
        assertRejected(wa, cfg, Map.of("x-hub-signature-256", sign("wrong", body)), body, Instant.now());
        assertRejected(wa, cfg, Map.of(), body, Instant.now());
        assertRejected(wa, cfg, Map.of("x-hub-signature-256", Hmac.sha256Hex("app-secret", b(body))), body, Instant.now()); // no "sha256=" prefix
        assertRejected(wa, Map.of(), Map.of("x-hub-signature-256", sign("app-secret", body)), body, Instant.now());
    }

    @Test
    void whatsappHandshakeChecksTheVerifyToken() throws Exception {
        Map<String, Object> cfg = Map.of("verifyToken", "vt");
        assertEquals("999", wa.handshake(cfg, Map.of("hub.mode", "subscribe", "hub.verify_token", "vt", "hub.challenge", "999"), null).orElseThrow());
        assertThrows(WebhookProvider.Rejected.class, () -> wa.handshake(cfg, Map.of("hub.mode", "subscribe", "hub.verify_token", "bad", "hub.challenge", "1"), null));
        assertThrows(WebhookProvider.Rejected.class, () -> wa.handshake(Map.of(), Map.of("hub.mode", "subscribe", "hub.verify_token", "", "hub.challenge", "1"), null));
        assertTrue(wa.handshake(cfg, Map.of(), Map.of()).isEmpty());
    }

    private static Map<String, Object> waPayload(String from, String type, String text) {
        Map<String, Object> msg = type.equals("text")
                ? Map.of("from", from, "id", "wamid." + from, "timestamp", "1", "type", "text", "text", Map.of("body", text))
                : Map.of("from", from, "id", "wamid." + from, "timestamp", "1", "type", type);
        return Map.of("object", "whatsapp_business_account", "entry", List.of(Map.of("changes", List.of(Map.of("value", Map.of(
                "messages", List.of(msg), "contacts", List.of(Map.of("wa_id", from, "profile", Map.of("name", "Ann")))))))));
    }

    @Test
    void whatsappEventsOnlyFromAllowedNumbersAndOnlyText() {
        Map<String, Object> cfg = Map.of("allowedNumbers", List.of("628123"));
        var ok = wa.events(cfg, waPayload("628123", "text", "hi there"));
        assertEquals(1, ok.size());
        assertEquals("hi there", ok.get(0).data().get("text"));
        assertEquals("Ann", ok.get(0).data().get("name"));
        assertEquals("wamid.628123", ok.get(0).id());
        assertTrue(wa.events(cfg, waPayload("999", "text", "stranger")).isEmpty());
        assertTrue(wa.events(cfg, waPayload("628123", "image", null)).isEmpty());
        assertTrue(wa.events(Map.of(), waPayload("628123", "text", "x")).isEmpty(), "no allow-list means nothing");
    }

    // ---------------------------------------------------------------- senders

    private HttpServer server;
    private final List<String> seen = new CopyOnWriteArrayList<>();
    private volatile String slackAnswer = "{\"ok\":true,\"ts\":\"1700.1\",\"channel\":\"C1\"}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/chat.postMessage", ex -> {
            seen.add(ex.getRequestURI().getPath() + " auth=" + ex.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, slackAnswer);
        });
        server.createContext("/v21.0/105555555555/messages", ex -> {
            seen.add(ex.getRequestURI().getPath() + " auth=" + ex.getRequestHeaders().getFirst("Authorization") + " "
                    + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, "{\"messages\":[{\"id\":\"wamid.OUT\"}]}");
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String json) throws IOException {
        byte[] out = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, out.length);
        ex.getResponseBody().write(out);
        ex.close();
    }

    private String base() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private static final String SLACK_TOKEN = "xoxb-1234567890-abcdefghijkl";
    private static final String WA_TOKEN = "EAAG-0123456789abcdefghij";

    @Test
    void slackSenderPostsWithBearerAndSurfacesErrorsHonestly() throws Exception {
        var send = new Slack.Sender(new UrlGuard(true, Set.of()));
        Map<String, Object> cfg = Map.of("botToken", SLACK_TOKEN, "baseUrl", base());
        Map<String, Object> out = send.call(cfg, Map.of("channel", "C1", "text", "On it", "threadTs", "1699.9"));
        assertEquals("1700.1", out.get("ts"));
        String req = seen.get(0);
        assertTrue(req.contains("auth=Bearer " + SLACK_TOKEN) && req.contains("\"channel\":\"C1\"") && req.contains("\"thread_ts\":\"1699.9\""), req);

        slackAnswer = "{\"ok\":false,\"error\":\"ratelimited\"}";
        var rate = assertThrows(ServiceHandler.ConnectorException.class, () -> send.call(cfg, Map.of("channel", "C1", "text", "x")));
        assertTrue(rate.retryable());
        slackAnswer = "{\"ok\":false,\"error\":\"channel_not_found\"}";
        var gone = assertThrows(ServiceHandler.ConnectorException.class, () -> send.call(cfg, Map.of("channel", "C1", "text", "x")));
        assertFalse(gone.retryable());
        assertFalse(gone.getMessage().contains(SLACK_TOKEN));

        assertThrows(ServiceHandler.ConnectorException.class, () -> send.call(Map.of("botToken", "nope", "baseUrl", base()), Map.of("channel", "C1", "text", "x")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> send.call(cfg, Map.of("text", "no channel")));
    }

    @Test
    void whatsappSenderPostsToTheGraphApiAndValidatesTheRecipient() throws Exception {
        var send = new WhatsApp.Sender(new UrlGuard(true, Set.of()));
        Map<String, Object> cfg = Map.of("accessToken", WA_TOKEN, "phoneNumberId", "105555555555", "baseUrl", base());
        Map<String, Object> out = send.call(cfg, Map.of("to", "628123", "text", "Hello"));
        assertEquals("wamid.OUT", out.get("messageId"));
        String req = seen.get(0);
        assertTrue(req.contains("/v21.0/105555555555/messages") && req.contains("auth=Bearer " + WA_TOKEN) && req.contains("\"messaging_product\":\"whatsapp\"")
                && req.contains("\"to\":\"628123\""), req);

        assertThrows(ServiceHandler.ConnectorException.class, () -> send.call(cfg, Map.of("to", "+628123", "text", "x")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> send.call(cfg, Map.of("to", "628123", "text", "x".repeat(5000))));
        assertThrows(ServiceHandler.ConnectorException.class,
                () -> send.call(Map.of("accessToken", WA_TOKEN, "phoneNumberId", "105555/../x", "baseUrl", base()), Map.of("to", "628123", "text", "x")));
        assertThrows(ServiceHandler.ConnectorException.class,
                () -> send.call(Map.of("accessToken", WA_TOKEN, "phoneNumberId", "105555555555", "apiVersion", "v1/../..", "baseUrl", base()), Map.of("to", "628123", "text", "x")));
    }

    @Test
    void strictGuardBlocksProviderBaseUrlsPointingInside() {
        assertThrows(ServiceHandler.ConnectorException.class, () -> Connectors.defaults().get("slack")
                .call(Map.of("botToken", SLACK_TOKEN, "baseUrl", base()), Map.of("channel", "C1", "text", "x")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> Connectors.defaults().get("whatsapp")
                .call(Map.of("accessToken", WA_TOKEN, "phoneNumberId", "105555555555", "baseUrl", base()), Map.of("to", "628123", "text", "x")));
    }
}
