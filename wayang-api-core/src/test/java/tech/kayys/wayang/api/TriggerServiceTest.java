package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.*;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tech.kayys.wayang.connectors.Connectors;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WaitRegistry;

class TriggerServiceTest {

    private static final String TOKEN = "123456:ABCDEFGHIJKLMNOPQRSTUVWXYZabcdef";

    private Instant now = Instant.parse("2026-01-01T00:00:00Z");
    private final Clock clock = new Clock() {
        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    };
    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final EventBus bus = new EventBus();
    private final RunEngine engine = new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, clock);
    private final WayangApi api = new WayangApi(store, engine, waits, bus, clock);
    private final TriggerStore triggers = new TriggerStore.InMemory();
    private final Map<String, String> secretValues = new java.util.HashMap<>();

    private final Principal admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));
    private final Principal otherAdmin = new Principal("globex", "gus", Set.of(Role.ADMIN));

    private final List<Map<String, Object>> batch = new ArrayList<>();
    private String nextCursor = "c1";
    private final List<String> seenCursors = new ArrayList<>();
    private RuntimeException pollFailure;

    private final Poller fake = (cfg, cursor) -> {
        seenCursors.add(cursor);
        if (pollFailure != null) {
            throw pollFailure;
        }
        return new Poller.Result(List.copyOf(batch), nextCursor);
    };

    private TriggerService service(Map<String, Poller> pollers) {
        return new TriggerService(triggers, api, pollers, (tenant, name) -> secretValues.get(tenant + "/" + name), clock);
    }

    private final TriggerService svc = service(Map.of("fake", fake));

    private void publishProcess(Principal p) {
        Map<String, Object> proc = Map.of("id", "inbox", "name", "Inbox", "elements", List.of(
                Map.of("id", "s", "type", "START_EVENT"),
                Map.of("id", "t", "type", "USER_TASK", "config", Map.of("title", "Reply")),
                Map.of("id", "e", "type", "END_EVENT")),
                "flows", List.of(Map.of("id", "f1", "source", "s", "target", "t"), Map.of("id", "f2", "source", "t", "target", "e")));
        api.saveDraft(p, DefinitionKind.PROCESS, "inbox", proc, null);
        api.publish(p, DefinitionKind.PROCESS, "inbox");
    }

    private int runCount() {
        return api.listRuns(admin, "inbox", 100).size();
    }

    @Test
    void theInMemoryStoreSatisfiesTheContract() {
        tech.kayys.wayang.api.testkit.TriggerStoreContract.verify(TriggerStore.InMemory::new);
    }

    // ------------------------------------------------------------- webhook

    @Test
    void webhookStartsARunWithTheRightSecretOnly() {
        publishProcess(admin);
        var created = svc.create(admin, "hook1", Trigger.Kind.WEBHOOK, "inbox", null);
        assertNotNull(created.webhookSecret());
        assertTrue(!created.webhookSecret().equals(created.trigger().secretHash())); // only a hash is stored
        assertFalse(created.trigger().toString().contains(created.webhookSecret()));

        RunView run = svc.fire("acme", "hook1", created.webhookSecret(), Map.of("subject", "hi"));
        assertEquals("inbox", run.processId());
        assertEquals(1, runCount());

        ApiException wrong = assertThrows(ApiException.class, () -> svc.fire("acme", "hook1", "nope", Map.of()));
        ApiException missing = assertThrows(ApiException.class, () -> svc.fire("acme", "ghost", created.webhookSecret(), Map.of()));
        ApiException otherTenant = assertThrows(ApiException.class, () -> svc.fire("globex", "hook1", created.webhookSecret(), Map.of()));
        assertEquals(ApiException.Code.UNAUTHORIZED, wrong.code());
        assertEquals(wrong.getMessage(), missing.getMessage());
        assertEquals(wrong.getMessage(), otherTenant.getMessage());
        assertEquals(1, runCount(), "refused calls start nothing");
    }

    @Test
    void aDisabledWebhookIsRefused() {
        publishProcess(admin);
        var created = svc.create(admin, "hook1", Trigger.Kind.WEBHOOK, "inbox", null);
        svc.setEnabled(admin, "hook1", false);
        assertThrows(ApiException.class, () -> svc.fire("acme", "hook1", created.webhookSecret(), Map.of()));
    }

    @Test
    void oversizedPayloadIsRefused() {
        publishProcess(admin);
        var created = svc.create(admin, "hook1", Trigger.Kind.WEBHOOK, "inbox", null);
        String big = "x".repeat(70_000);
        ApiException e = assertThrows(ApiException.class, () -> svc.fire("acme", "hook1", created.webhookSecret(), Map.of("b", big)));
        assertEquals(ApiException.Code.BAD_REQUEST, e.code());
    }

    @Test
    void creatingNeedsBothDefinitionWriteAndRunStart() {
        assertThrows(ApiException.class, () -> svc.create(viewer, "h", Trigger.Kind.WEBHOOK, "inbox", null));
        Principal writeOnly = new Principal("acme", "wo", Set.of(), EnumSet.of(Permission.DEFINITION_WRITE));
        Principal startOnly = new Principal("acme", "so", Set.of(), EnumSet.of(Permission.RUN_START));
        assertThrows(ApiException.class, () -> svc.create(writeOnly, "h", Trigger.Kind.WEBHOOK, "inbox", null));
        assertThrows(ApiException.class, () -> svc.create(startOnly, "h", Trigger.Kind.WEBHOOK, "inbox", null));
    }

    @Test
    void triggersAreTenantScoped() {
        svc.create(admin, "hook1", Trigger.Kind.WEBHOOK, "inbox", null);
        assertEquals(1, svc.list(admin).size());
        assertEquals(0, svc.list(otherAdmin).size());
        assertThrows(ApiException.class, () -> svc.delete(otherAdmin, "hook1"));
        assertEquals(1, svc.list(admin).size());
    }

    // ---------------------------------------------------------------- poll

    private void pollTrigger(String id) {
        svc.create(admin, id, Trigger.Kind.POLL, "inbox", Map.of("source", "fake", "everySeconds", 10));
    }

    @Test
    void pollStartsOneRunPerEventAndPersistsTheCursor() {
        publishProcess(admin);
        pollTrigger("p1");
        batch.addAll(List.of(Map.of("text", "a"), Map.of("text", "b")));
        assertEquals(2, svc.tick());
        assertEquals(2, runCount());
        assertEquals("c1", triggers.get("acme", "p1").orElseThrow().cursor());

        batch.clear();
        nextCursor = "c2";
        assertEquals(0, svc.tick(), "not due yet: no second poll");
        assertEquals(1, seenCursors.size());
        now = now.plusSeconds(11);
        svc.tick();
        assertEquals("c1", seenCursors.get(1), "the next poll continues from the stored cursor");
    }

    @Test
    void aSuspendedTenantsTriggersNeitherPollNorFire() {
        publishProcess(admin);
        pollTrigger("p1");
        var hook = svc.create(admin, "hook1", Trigger.Kind.WEBHOOK, "inbox", null);
        batch.add(Map.of("text", "a"));
        boolean[] suspended = {true};
        svc.withTenantGate(t -> !(suspended[0] && t.equals("acme")));
        assertEquals(0, svc.tick());
        assertTrue(seenCursors.isEmpty(), "a suspended tenant is not even polled");
        assertEquals(ApiException.Code.UNAUTHORIZED,
                assertThrows(ApiException.class, () -> svc.fire("acme", "hook1", hook.webhookSecret(), Map.of())).code());
        assertThrows(ApiException.class, () -> svc.receive("acme", "hook1", "POST", Map.of(), Map.of("X-Wayang-Secret", hook.webhookSecret()), "{}".getBytes()));
        assertEquals(0, runCount());
        suspended[0] = false; // reactivated: everything works again, nothing was lost
        assertEquals(1, svc.tick());
        assertEquals("inbox", svc.fire("acme", "hook1", hook.webhookSecret(), Map.of()).processId());
    }

    @Test
    void aFailedStartDoesNotAdvanceTheCursorSoNothingIsLostAndItBacksOff() {
        // the process is not published yet: runs cannot start
        pollTrigger("p1");
        batch.add(Map.of("text", "important"));
        assertEquals(0, svc.tick());
        Trigger t = triggers.get("acme", "p1").orElseThrow();
        assertNull(t.cursor(), "cursor stays where it was");
        assertNotNull(t.lastError());
        assertEquals(1, t.consecutiveFailures());

        now = now.plusSeconds(11);
        assertEquals(0, svc.tick(), "backing off: 10 s became 20 s");
        assertEquals(1, seenCursors.size());

        publishProcess(admin);
        now = now.plusSeconds(20);
        assertEquals(1, svc.tick(), "the message was kept and is delivered once the process exists");
        assertEquals(0, triggers.get("acme", "p1").orElseThrow().consecutiveFailures());
        assertNull(triggers.get("acme", "p1").orElseThrow().lastError());
    }

    @Test
    void secretsInPollConfigAreResolvedPerTenantAndRedactedFromErrors() {
        secretValues.put("acme/BOT", "super-secret-value");
        List<Map<String, Object>> seen = new ArrayList<>();
        Poller leaky = (cfg, c) -> {
            seen.add(cfg);
            throw new IllegalStateException("boom while using super-secret-value");
        };
        TriggerService s = service(Map.of("leaky", leaky));
        s.create(admin, "p1", Trigger.Kind.POLL, "inbox", Map.of("source", "leaky", "token", "${secret:BOT}"));
        s.tick();
        assertEquals("super-secret-value", seen.get(0).get("token"));
        String err = triggers.get("acme", "p1").orElseThrow().lastError();
        assertFalse(err.contains("super-secret-value"), err);
        assertTrue(err.contains("***"));
        assertFalse(triggers.get("acme", "p1").orElseThrow().config().toString().contains("super-secret-value"));
    }

    @Test
    void aMissingSecretIsAnErrorNotAnEmptyValueAndTenantsDoNotShareSecrets() {
        secretValues.put("globex/BOT", "theirs");
        Poller p = (cfg, c) -> new Poller.Result(List.of(), null);
        TriggerService s = service(Map.of("p", p));
        s.create(admin, "p1", Trigger.Kind.POLL, "inbox", Map.of("source", "p", "token", "${secret:BOT}"));
        s.tick();
        assertTrue(triggers.get("acme", "p1").orElseThrow().lastError().contains("not set"));
    }

    @Test
    void unknownSourceIsRefusedAtCreation() {
        assertThrows(ApiException.class, () -> svc.create(admin, "p1", Trigger.Kind.POLL, "inbox", Map.of("source", "nope")));
        assertThrows(ApiException.class, () -> svc.create(admin, "p1", Trigger.Kind.POLL, "inbox", Map.of("source", "fake", "everySeconds", 1)));
    }

    // ------------------------------------------------- provider webhooks

    private static String hmac(String secret, String data) {
        try {
            javax.crypto.Mac mac = javax.crypto.Mac.getInstance("HmacSHA256");
            mac.init(new javax.crypto.spec.SecretKeySpec(secret.getBytes(StandardCharsets.UTF_8), "HmacSHA256"));
            return java.util.HexFormat.of().formatHex(mac.doFinal(data.getBytes(StandardCharsets.UTF_8)));
        } catch (java.security.GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
    }

    private TriggerService providerService() {
        return new TriggerService(triggers, api, Map.of(), Connectors.webhookProviders(),
                (tenant, name) -> secretValues.get(tenant + "/" + name), clock);
    }

    private Map<String, String> slackHeaders(String body) {
        String ts = Long.toString(now.getEpochSecond());
        return Map.of("X-Slack-Request-Timestamp", ts, "X-Slack-Signature", "v0=" + hmac("slack-secret", "v0:" + ts + ":" + body));
    }

    private String slackBody(String eventId, String channel, String text) {
        return "{\"type\":\"event_callback\",\"event_id\":\"" + eventId + "\",\"event\":{\"type\":\"message\",\"channel\":\"" + channel
                + "\",\"user\":\"U1\",\"text\":\"" + text + "\",\"ts\":\"1.1\"}}";
    }

    private TriggerService slackTrigger() {
        publishProcess(admin);
        secretValues.put("acme/SLACK_SIGNING", "slack-secret");
        TriggerService s = providerService();
        var created = s.create(admin, "sl", Trigger.Kind.WEBHOOK, "inbox",
                Map.of("auth", "slack", "signingSecret", "${secret:SLACK_SIGNING}", "allowedChannels", List.of("D1")));
        assertNull(created.webhookSecret(), "provider webhooks are authenticated by the provider's signature");
        return s;
    }

    @Test
    void slackWebhookStartsARunOnlyForSignedAllowedMessages() {
        TriggerService s = slackTrigger();
        String body = slackBody("Ev1", "D1", "remind me at 5");
        var r = s.receive("acme", "sl", "POST", Map.of(), slackHeaders(body), body.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, r.status());
        assertEquals(1, r.runs());
        assertEquals(1, runCount());

        String other = slackBody("Ev2", "C99", "not my channel");
        assertEquals(0, s.receive("acme", "sl", "POST", Map.of(), slackHeaders(other), other.getBytes(StandardCharsets.UTF_8)).runs());
        assertEquals(1, runCount());
    }

    @Test
    void slackRetriesAreDeduplicatedByEventId() {
        TriggerService s = slackTrigger();
        String body = slackBody("Ev1", "D1", "once please");
        s.receive("acme", "sl", "POST", Map.of(), slackHeaders(body), body.getBytes(StandardCharsets.UTF_8));
        var again = s.receive("acme", "sl", "POST", Map.of(), slackHeaders(body), body.getBytes(StandardCharsets.UTF_8));
        assertEquals(200, again.status());
        assertEquals(0, again.runs());
        assertEquals(1, runCount());
    }

    @Test
    void anUnsignedOrBadlySignedSlackRequestIsRefusedAndStartsNothing() {
        TriggerService s = slackTrigger();
        String body = slackBody("Ev1", "D1", "hi");
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        ApiException none = assertThrows(ApiException.class, () -> s.receive("acme", "sl", "POST", Map.of(), Map.of(), raw));
        Map<String, String> bad = Map.of("x-slack-request-timestamp", Long.toString(now.getEpochSecond()), "x-slack-signature", "v0=00");
        ApiException wrong = assertThrows(ApiException.class, () -> s.receive("acme", "sl", "POST", Map.of(), bad, raw));
        ApiException missing = assertThrows(ApiException.class, () -> s.receive("acme", "ghost", "POST", Map.of(), slackHeaders(body), raw));
        assertEquals(ApiException.Code.UNAUTHORIZED, none.code());
        assertEquals(none.getMessage(), wrong.getMessage());
        assertEquals(none.getMessage(), missing.getMessage());
        assertEquals(0, runCount());
    }

    @Test
    void slackUrlVerificationReturnsTheChallengeWithoutStartingARun() {
        TriggerService s = slackTrigger();
        String body = "{\"type\":\"url_verification\",\"challenge\":\"3eZbrw1\"}";
        var r = s.receive("acme", "sl", "POST", Map.of(), slackHeaders(body), body.getBytes(StandardCharsets.UTF_8));
        assertEquals("3eZbrw1", r.body());
        assertEquals(0, runCount());
        String unsigned = body;
        assertThrows(ApiException.class, () -> s.receive("acme", "sl", "POST", Map.of(), Map.of(), unsigned.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void whatsappHandshakeAndSignedMessage() {
        publishProcess(admin);
        secretValues.put("acme/WA_APP", "app-secret");
        secretValues.put("acme/WA_VERIFY", "verify-me");
        TriggerService s = providerService();
        s.create(admin, "wa", Trigger.Kind.WEBHOOK, "inbox", Map.of("auth", "whatsapp", "appSecret", "${secret:WA_APP}",
                "verifyToken", "${secret:WA_VERIFY}", "allowedNumbers", List.of("628123")));

        var hs = s.receive("acme", "wa", "GET", Map.of("hub.mode", "subscribe", "hub.verify_token", "verify-me", "hub.challenge", "4242"), Map.of(), null);
        assertEquals("4242", hs.body());
        assertThrows(ApiException.class, () -> s.receive("acme", "wa", "GET",
                Map.of("hub.mode", "subscribe", "hub.verify_token", "guess", "hub.challenge", "1"), Map.of(), null));

        String body = "{\"entry\":[{\"changes\":[{\"value\":{\"messages\":[{\"from\":\"628123\",\"id\":\"wamid.1\",\"timestamp\":\"1\",\"type\":\"text\",\"text\":{\"body\":\"hello\"}}]}}]}]}";
        Map<String, String> h = Map.of("X-Hub-Signature-256", "sha256=" + hmac("app-secret", body));
        assertEquals(1, s.receive("acme", "wa", "POST", Map.of(), h, body.getBytes(StandardCharsets.UTF_8)).runs());
        assertEquals(0, s.receive("acme", "wa", "POST", Map.of(), h, body.getBytes(StandardCharsets.UTF_8)).runs(), "same message id is not processed twice");
        assertEquals(1, runCount());
        assertThrows(ApiException.class, () -> s.receive("acme", "wa", "POST", Map.of(), Map.of("X-Hub-Signature-256", "sha256=" + hmac("x", body)),
                body.getBytes(StandardCharsets.UTF_8)));
    }

    @Test
    void credentialsInTriggerConfigMustBeSecretReferences() {
        TriggerService s = providerService();
        for (String key : new String[] {"signingSecret", "appSecret", "botToken", "accessToken", "verifyToken", "password", "apiKey"}) {
            ApiException e = assertThrows(ApiException.class, () -> s.create(admin, "x", Trigger.Kind.WEBHOOK, "inbox",
                    Map.of("auth", "slack", key, "literal-credential")), key);
            assertEquals(ApiException.Code.BAD_REQUEST, e.code(), key);
            assertFalse(e.getMessage().contains("literal-credential"));
        }
        assertThrows(ApiException.class, () -> s.create(admin, "y", Trigger.Kind.POLL, "inbox", Map.of("source", "x", "botToken", "123:abc")));
        assertThrows(ApiException.class, () -> s.create(admin, "z", Trigger.Kind.WEBHOOK, "inbox", Map.of("auth", "nonsense")));
        // a secret reference embedded in other text is not a pure reference either
        assertThrows(ApiException.class, () -> s.create(admin, "w", Trigger.Kind.WEBHOOK, "inbox", Map.of("auth", "slack", "signingSecret", "x${secret:A}")));
    }

    @Test
    void aMissingSecretMakesTheWebhookUnavailableNotOpen() {
        publishProcess(admin);
        TriggerService s = providerService(); // SLACK_SIGNING never set
        s.create(admin, "sl", Trigger.Kind.WEBHOOK, "inbox", Map.of("auth", "slack", "signingSecret", "${secret:SLACK_SIGNING}", "allowedChannels", List.of("D1")));
        String body = slackBody("Ev1", "D1", "hi");
        ApiException e = assertThrows(ApiException.class, () -> s.receive("acme", "sl", "POST", Map.of(), slackHeaders(body), body.getBytes(StandardCharsets.UTF_8)));
        assertEquals(ApiException.Code.UNAVAILABLE, e.code());
        assertEquals(0, runCount());
    }

    @Test
    void theSharedSecretWebhookStillWorksThroughReceive() {
        publishProcess(admin);
        var created = svc.create(admin, "hook1", Trigger.Kind.WEBHOOK, "inbox", null);
        var r = svc.receive("acme", "hook1", "POST", Map.of(), Map.of("x-wayang-secret", created.webhookSecret()), "{\"a\":1}".getBytes(StandardCharsets.UTF_8));
        assertEquals(202, r.status());
        assertEquals(1, runCount());
        assertThrows(ApiException.class, () -> svc.receive("acme", "hook1", "POST", Map.of(), Map.of(), "{}".getBytes(StandardCharsets.UTF_8)));
        assertThrows(ApiException.class, () -> svc.receive("acme", "hook1", "POST", Map.of("x", "y"),
                Map.of("x-wayang-secret", created.webhookSecret()), "[1]".getBytes(StandardCharsets.UTF_8)));
    }

    // ------------------------------------------------------------ telegram

    private HttpServer telegram;
    private final List<String> telegramSeen = new CopyOnWriteArrayList<>();
    private String updatesJson = "{\"ok\":true,\"result\":[]}";

    @BeforeEach
    void startTelegram() throws IOException {
        telegram = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        telegram.createContext("/bot" + TOKEN + "/getUpdates", ex -> {
            telegramSeen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + "?" + ex.getRequestURI().getRawQuery());
            reply(ex, updatesJson);
        });
        telegram.createContext("/bot" + TOKEN + "/sendMessage", ex -> {
            telegramSeen.add(ex.getRequestMethod() + " " + ex.getRequestURI().getPath() + " " + new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            reply(ex, "{\"ok\":true,\"result\":{\"message_id\":77,\"chat\":{\"id\":111}}}");
        });
        telegram.start();
    }

    @AfterEach
    void stopTelegram() {
        telegram.stop(0);
    }

    private static void reply(com.sun.net.httpserver.HttpExchange ex, String json) throws IOException {
        byte[] b = json.getBytes(StandardCharsets.UTF_8);
        ex.getResponseHeaders().add("Content-Type", "application/json");
        ex.sendResponseHeaders(200, b.length);
        ex.getResponseBody().write(b);
        ex.close();
    }

    private String base() {
        return "http://127.0.0.1:" + telegram.getAddress().getPort();
    }

    private TriggerService telegramService() {
        return service(Connectors.pollers(new UrlGuard(true, Set.of())));
    }

    @Test
    void telegramMessagesFromAllowedChatsStartRunsAndOthersAreDropped() {
        publishProcess(admin);
        secretValues.put("acme/TG", TOKEN);
        updatesJson = "{\"ok\":true,\"result\":["
                + "{\"update_id\":10,\"message\":{\"message_id\":1,\"date\":1,\"chat\":{\"id\":111,\"type\":\"private\"},\"from\":{\"id\":5,\"username\":\"ann\"},\"text\":\"hello bot\"}},"
                + "{\"update_id\":11,\"message\":{\"message_id\":2,\"date\":2,\"chat\":{\"id\":999,\"type\":\"private\"},\"from\":{\"id\":6},\"text\":\"I am a stranger\"}},"
                + "{\"update_id\":12,\"message\":{\"message_id\":3,\"date\":3,\"chat\":{\"id\":111,\"type\":\"private\"},\"photo\":[]}}"
                + "]}";
        TriggerService s = telegramService();
        s.create(admin, "tg", Trigger.Kind.POLL, "inbox",
                Map.of("source", "telegram", "botToken", "${secret:TG}", "baseUrl", base(), "allowedChatIds", List.of(111), "everySeconds", 5));

        assertEquals(1, s.tick(), "only the allowed chat's text message starts a run");
        assertEquals(1, runCount());
        assertEquals("13", triggers.get("acme", "tg").orElseThrow().cursor(), "cursor moves past dropped updates too");

        RunView run = api.listRuns(admin, "inbox", 10).get(0);
        @SuppressWarnings("unchecked")
        Map<String, Object> event = (Map<String, Object>) run.variables().get("event");
        assertEquals("hello bot", event.get("text"));
        assertFalse(run.variables().toString().contains(TOKEN), "the bot token never reaches run data");

        updatesJson = "{\"ok\":true,\"result\":[]}";
        now = now.plusSeconds(6);
        s.tick();
        assertTrue(telegramSeen.get(telegramSeen.size() - 1).contains("offset=13"), telegramSeen.toString());
    }

    @Test
    void telegramTriggerWithoutAnAllowListNeverStartsRuns() {
        publishProcess(admin);
        secretValues.put("acme/TG", TOKEN);
        updatesJson = "{\"ok\":true,\"result\":[{\"update_id\":1,\"message\":{\"message_id\":1,\"chat\":{\"id\":111},\"text\":\"x\"}}]}";
        TriggerService s = telegramService();
        s.create(admin, "tg", Trigger.Kind.POLL, "inbox", Map.of("source", "telegram", "botToken", "${secret:TG}", "baseUrl", base()));
        assertEquals(0, s.tick());
        assertEquals(0, runCount());
        assertTrue(triggers.get("acme", "tg").orElseThrow().lastError().contains("allowedChatIds"));
        assertTrue(telegramSeen.isEmpty(), "refused before any request is made");
    }

    @Test
    void telegramBotErrorKeepsTheCursorAndTheTokenOutOfTheError() {
        publishProcess(admin);
        secretValues.put("acme/TG", TOKEN);
        updatesJson = "{\"ok\":false,\"description\":\"Unauthorized\"}";
        TriggerService s = telegramService();
        s.create(admin, "tg", Trigger.Kind.POLL, "inbox",
                Map.of("source", "telegram", "botToken", "${secret:TG}", "baseUrl", base(), "allowedChatIds", List.of(111)));
        s.tick();
        Trigger t = triggers.get("acme", "tg").orElseThrow();
        assertNotNull(t.lastError());
        assertFalse(t.lastError().contains(TOKEN));
        assertNull(t.cursor());
    }

    @Test
    void telegramSendMessageThroughTheConnector() throws Exception {
        ServiceHandler.Connector send = Connectors.defaults(new UrlGuard(true, Set.of())).get("telegram");
        Map<String, Object> out = send.call(Map.of("botToken", TOKEN, "baseUrl", base()), Map.of("chatId", 111, "text", "On it", "replyTo", 5));
        assertEquals(77, ((Number) out.get("messageId")).intValue());
        String body = telegramSeen.get(telegramSeen.size() - 1);
        assertTrue(body.contains("\"chat_id\":111") && body.contains("\"text\":\"On it\"") && body.contains("\"reply_to_message_id\":5"), body);

        assertThrows(ServiceHandler.ConnectorException.class, () -> send.call(Map.of("botToken", "bad", "baseUrl", base()), Map.of("chatId", 1, "text", "x")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> send.call(Map.of("botToken", TOKEN, "baseUrl", base()), Map.of("chatId", 1, "text", "x".repeat(4097))));
        assertThrows(ServiceHandler.ConnectorException.class, () -> send.call(Map.of("botToken", TOKEN, "baseUrl", base()), Map.of("text", "no chat")));
    }

    @Test
    void theStrictGuardStillBlocksATelegramBaseUrlPointingInside() {
        ServiceHandler.Connector send = Connectors.defaults().get("telegram");
        assertThrows(ServiceHandler.ConnectorException.class,
                () -> send.call(Map.of("botToken", TOKEN, "baseUrl", base()), Map.of("chatId", 1, "text", "x")));
    }

    // ------------------------------------------------- GitHub webhook and RSS poller (the Studio's templates)

    private TriggerService githubTrigger() {
        publishProcess(admin);
        secretValues.put("acme/GH_HOOK", "gh-hook-secret");
        TriggerService s = providerService();
        var created = s.create(admin, "gh", Trigger.Kind.WEBHOOK, "inbox",
                Map.of("auth", "github", "secret", "${secret:GH_HOOK}", "allowedRepos", List.of("me/*"), "events", List.of("issues")));
        assertNull(created.webhookSecret());
        return s;
    }

    private static String ghBody(String repo, String sender, int number) {
        return "{\"action\":\"opened\",\"issue\":{\"number\":" + number + ",\"title\":\"T\",\"body\":\"B\",\"updated_at\":\"u\",\"html_url\":\"h\"},"
                + "\"repository\":{\"full_name\":\"" + repo + "\"},\"sender\":{\"login\":\"u\",\"type\":\"" + sender + "\"}}";
    }

    @Test
    void githubWebhookStartsRunsOnlyForSignedAllowedHumanEventsAndDedupes() {
        TriggerService s = githubTrigger();
        String body = ghBody("me/app", "User", 1);
        byte[] raw = body.getBytes(StandardCharsets.UTF_8);
        Map<String, String> ok = Map.of("x-hub-signature-256", "sha256=" + hmac("gh-hook-secret", body));
        assertEquals(1, s.receive("acme", "gh", "POST", Map.of(), ok, raw).runs());
        assertEquals(0, s.receive("acme", "gh", "POST", Map.of(), ok, raw).runs(), "a redelivery does not start a second run");
        String other = ghBody("evil/app", "User", 2);
        assertEquals(0, s.receive("acme", "gh", "POST", Map.of(), Map.of("x-hub-signature-256", "sha256=" + hmac("gh-hook-secret", other)),
                other.getBytes(StandardCharsets.UTF_8)).runs());
        String bot = ghBody("me/app", "Bot", 3);
        assertEquals(0, s.receive("acme", "gh", "POST", Map.of(), Map.of("x-hub-signature-256", "sha256=" + hmac("gh-hook-secret", bot)),
                bot.getBytes(StandardCharsets.UTF_8)).runs());
        assertEquals(1, runCount());
        assertThrows(ApiException.class, () -> s.receive("acme", "gh", "POST", Map.of(), Map.of("x-hub-signature-256", "sha256=" + hmac("wrong", body)), raw));
        assertThrows(ApiException.class, () -> s.receive("acme", "gh", "POST", Map.of(), Map.of(), raw));
    }

    @Test
    void githubAndRssTriggersRefuseLiteralSecretsAndUnknownSources() {
        publishProcess(admin);
        TriggerService s = providerService();
        assertThrows(ApiException.class, () -> s.create(admin, "gh2", Trigger.Kind.WEBHOOK, "inbox",
                Map.of("auth", "github", "secret", "literal-secret-value", "allowedRepos", List.of("me/*"))));
        TriggerService withRss = new TriggerService(triggers, api, Connectors.pollers(UrlGuard.strict()), Connectors.webhookProviders(),
                (tenant, name) -> secretValues.get(tenant + "/" + name), clock);
        var rss = withRss.create(admin, "news", Trigger.Kind.POLL, "inbox",
                Map.of("source", "rss", "url", "https://example.org/feed.xml", "limit", 20, "everySeconds", 900));
        assertEquals("news", rss.trigger().id());
        assertThrows(ApiException.class, () -> withRss.create(admin, "bad", Trigger.Kind.POLL, "inbox", Map.of("source", "nope", "everySeconds", 60)));
    }

    // ------------------------------------------------------------------ schedules

    private Trigger sched(String id) {
        return triggers.get("acme", id).orElseThrow();
    }

    @Test
    void aScheduleRunsOnceAtItsTimeInItsTimeZoneAndCarriesItsInput() {
        publishProcess(admin);
        now = Instant.parse("2026-01-05T00:30:00Z"); // Monday 07:30 in Jakarta
        svc.create(admin, "brief", Trigger.Kind.SCHEDULE, "inbox", Map.of("cron", "0 8 * * 1-5", "timezone", "Asia/Jakarta", "input", Map.of("topic", "news")));
        assertEquals(0, svc.tick(), "not yet 08:00");
        now = Instant.parse("2026-01-05T01:00:00Z");
        assertEquals(1, svc.tick());
        assertEquals(1, runCount());
        var ev = (Map<?, ?>) api.listRuns(admin, "inbox", 10).get(0).variables().get("event");
        assertEquals("news", ev.get("topic"));
        assertEquals("2026-01-05T01:00:00Z", ev.get("scheduledFor"));
        assertEquals("Asia/Jakarta", ev.get("timezone"));
        assertEquals("brief", api.listRuns(admin, "inbox", 10).get(0).variables().get("trigger"));
        assertEquals(0, svc.tick(), "once, not on every tick of that minute");
        now = Instant.parse("2026-01-05T01:00:30Z");
        assertEquals(0, svc.tick());
        now = Instant.parse("2026-01-09T01:00:00Z"); // Friday: Tuesday's run was missed by days, so it is skipped, not run late
        assertEquals(0, svc.tick());
        assertEquals(1, runCount());
        now = Instant.parse("2026-01-10T01:00:00Z"); // Saturday: nothing
        assertEquals(0, svc.tick());
        now = Instant.parse("2026-01-12T01:00:00Z"); // Monday 08:00 in Jakarta
        assertEquals(1, svc.tick());
        assertEquals(2, runCount());
    }

    @Test
    void aRunMissedByMoreThanTheGraceIsSkippedNotRunLate() {
        publishProcess(admin);
        now = Instant.parse("2026-01-05T00:00:00Z");
        svc.create(admin, "brief", Trigger.Kind.SCHEDULE, "inbox", Map.of("cron", "0 8 * * *", "timezone", "UTC", "graceSeconds", 600));
        now = Instant.parse("2026-01-05T15:00:00Z"); // the server was down all morning
        assertEquals(0, svc.tick());
        assertEquals(0, runCount());
        assertTrue(sched("brief").lastError().contains("Skipped the run scheduled for 2026-01-05T08:00:00Z"), sched("brief").lastError());
        now = Instant.parse("2026-01-06T08:00:00Z");
        assertEquals(1, svc.tick(), "the next one is on time");
        now = Instant.parse("2026-01-07T08:09:59Z"); // inside the grace: late but still run
        assertEquals(1, svc.tick());
        assertEquals(2, runCount());
        assertNull(sched("brief").lastError());
    }

    @Test
    void aSwitchedOffScheduleDoesNotRunAndCountsFromNowWhenSwitchedBackOn() {
        publishProcess(admin);
        now = Instant.parse("2026-01-05T00:00:00Z");
        svc.create(admin, "brief", Trigger.Kind.SCHEDULE, "inbox", Map.of("cron", "0 8 * * *"));
        svc.setEnabled(admin, "brief", false);
        now = Instant.parse("2026-01-05T08:00:10Z");
        assertEquals(0, svc.tick());
        now = Instant.parse("2026-01-05T08:05:00Z");
        svc.setEnabled(admin, "brief", true);
        assertEquals(0, svc.tick(), "the 08:00 that came due while it was off is not run");
        now = Instant.parse("2026-01-06T08:00:00Z");
        assertEquals(1, svc.tick());
    }

    @Test
    void aSuspendedTenantsSchedulesDoNotRunAndAFailedStartIsRetriedWithinTheGrace() {
        publishProcess(admin);
        now = Instant.parse("2026-01-05T00:00:00Z");
        svc.create(admin, "brief", Trigger.Kind.SCHEDULE, "inbox", Map.of("cron", "0 8 * * *", "graceSeconds", 120));
        now = Instant.parse("2026-01-05T08:00:00Z");
        svc.withTenantGate(TenantGate.suspended(Set.of("acme")));
        assertEquals(0, svc.tick());
        svc.withTenantGate(TenantGate.ALLOW_ALL);
        // a second schedule points at a process that does not exist, so its start fails
        svc.create(admin, "ghost", Trigger.Kind.SCHEDULE, "nowhere", Map.of("cron", "0 8 * * *", "graceSeconds", 120));
        triggers.update(sched("ghost").with(true, null, Instant.parse("2026-01-05T07:00:00Z"), null, 0));
        assertEquals(1, svc.tick(), "brief runs; ghost cannot start");
        assertEquals(1, sched("ghost").consecutiveFailures());
        assertNotNull(sched("ghost").lastError());
        now = Instant.parse("2026-01-05T08:00:05Z");
        svc.tick();
        assertEquals(2, sched("ghost").consecutiveFailures(), "retried on the next tick");
        now = Instant.parse("2026-01-05T08:03:00Z");
        svc.tick();
        assertTrue(sched("ghost").lastError().contains("Skipped"), "after the grace it gives up on that run: " + sched("ghost").lastError());
    }

    @Test
    void scheduleConfigurationIsCheckedWhenCreated() {
        publishProcess(admin);
        for (Map<String, Object> bad : List.of(Map.<String, Object>of(), Map.<String, Object>of("cron", "* * *"), Map.<String, Object>of("cron", "0 8 * * *", "timezone", "Mars/X"),
                Map.<String, Object>of("cron", "0 8 * * *", "timezone", 7), Map.<String, Object>of("cron", "0 8 * * *", "graceSeconds", -1),
                Map.<String, Object>of("cron", "0 8 * * *", "graceSeconds", 90000), Map.<String, Object>of("cron", "0 8 * * *", "input", "text"),
                Map.<String, Object>of("cron", "0 8 * * *", "input", Map.of("x", "y".repeat(9000))), Map.<String, Object>of("cron", "0 8 * * *", "extra", 1),
                Map.<String, Object>of("cron", "0 8 * * *", "input", Map.of("apiKey", "sk-literal")))) {
            assertThrows(ApiException.class, () -> svc.create(admin, "bad", Trigger.Kind.SCHEDULE, "inbox", bad), bad.toString());
        }
        assertTrue(svc.create(admin, "good", Trigger.Kind.SCHEDULE, "inbox", Map.of("cron", "@daily")).webhookSecret() == null);
        assertEquals(1, triggers.list("acme").size());
        assertThrows(ApiException.class, () -> svc.create(viewer, "v", Trigger.Kind.SCHEDULE, "inbox", Map.of("cron", "@daily")));
    }

    // ------------------------------------------------------------- approvals in the chat

    private int tickWith(TriggerService t, Map<String, Object>... events) {
        batch.clear();
        batch.addAll(List.of(events));
        now = now.plusSeconds(60);
        return t.tick();
    }

    private static Map<String, Object> msg(long chat, String text) {
        return Map.of("chatId", chat, "text", text);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aYesOrNoFromTheSameChatAnswersTheOldestWaitingApprovalInsteadOfStartingARun() {
        publishProcess(admin);
        var t = service(Map.of("telegram", fake));
        t.create(admin, "chat", Trigger.Kind.POLL, "inbox", Map.of("source", "telegram", "approvals", true));
        assertEquals(1, tickWith(t, msg(7, "hello")));
        now = now.plusSeconds(5);
        assertEquals(1, tickWith(t, msg(7, "second question")));
        assertEquals(1, tickWith(t, msg(8, "yes")), "another chat's yes is an ordinary message");
        assertEquals(3, runCount());
        assertEquals(0, tickWith(t, msg(7, "Yes!")), "answers the OLDEST waiting approval of chat 7, starts nothing");
        assertEquals(3, runCount());
        var done = api.listRuns(admin, "inbox", 100).stream().filter(r -> r.status() == RunStatus.COMPLETED).toList();
        assertEquals(1, done.size());
        assertEquals("hello", ((Map<?, ?>) done.get(0).variables().get("event")).get("text"));
        assertTrue(String.valueOf(done.get(0).variables()).contains("approved=true"), String.valueOf(done.get(0).variables()));
        assertEquals(0, tickWith(t, msg(7, "no")));
        assertTrue(api.listRuns(admin, "inbox", 100).stream().anyMatch(r -> String.valueOf(r.variables()).contains("approved=false")));
        assertEquals(2, api.listRuns(admin, "inbox", 100).stream().filter(r -> r.status() == RunStatus.COMPLETED).count());
        assertEquals(1, tickWith(t, msg(7, "yes")), "nothing of chat 7 is waiting any more: an ordinary message");
        assertEquals(1, tickWith(t, msg(7, "yes please send it now")), "a sentence is a message, not an answer");
    }

    @Test
    void approvalsAreOffUnlessAskedForAndOnlyForChatTriggers() {
        publishProcess(admin);
        var t = service(Map.of("telegram", fake, "other", fake));
        t.create(admin, "plain", Trigger.Kind.POLL, "inbox", Map.of("source", "telegram"));
        assertEquals(1, tickWith(t, msg(7, "hello")));
        assertEquals(1, tickWith(t, msg(7, "yes")), "without approvals a yes is just a message");
        assertThrows(ApiException.class, () -> t.create(admin, "x", Trigger.Kind.POLL, "inbox", Map.of("source", "other", "approvals", true)));
        assertThrows(ApiException.class, () -> t.create(admin, "y", Trigger.Kind.POLL, "inbox", Map.of("source", "telegram", "approvals", "yes")));
        assertThrows(ApiException.class, () -> t.create(admin, "z", Trigger.Kind.SCHEDULE, "inbox", Map.of("cron", "@daily", "approvals", true)));
        assertThrows(ApiException.class, () -> t.create(admin, "w", Trigger.Kind.WEBHOOK, "inbox", Map.of("approvals", true)));
    }

    @Test
    void aYesInTheSlackChannelAnswersTheWaitingApprovalThroughTheSignedWebhook() {
        publishProcess(admin);
        secretValues.put("acme/SLACK_SIGNING", "slack-secret");
        TriggerService s = providerService();
        s.create(admin, "sl", Trigger.Kind.WEBHOOK, "inbox",
                Map.of("auth", "slack", "signingSecret", "${secret:SLACK_SIGNING}", "allowedChannels", List.of("D1"), "approvals", true));
        String ask = slackBody("Ev1", "D1", "draft something");
        assertEquals(1, s.receive("acme", "sl", "POST", Map.of(), slackHeaders(ask), ask.getBytes(StandardCharsets.UTF_8)).runs());
        String yes = slackBody("Ev2", "D1", "yes");
        assertEquals(0, s.receive("acme", "sl", "POST", Map.of(), slackHeaders(yes), yes.getBytes(StandardCharsets.UTF_8)).runs(), "an answer starts no run");
        assertEquals(1, runCount());
        assertEquals(RunStatus.COMPLETED, run0().status());
        String again = slackBody("Ev3", "D1", "yes");
        assertEquals(1, s.receive("acme", "sl", "POST", Map.of(), slackHeaders(again), again.getBytes(StandardCharsets.UTF_8)).runs(), "nothing waits any more");
    }

    private RunView run0() {
        return api.listRuns(admin, "inbox", 1).get(0);
    }

    @Test
    @SuppressWarnings("unchecked")
    void aChatOverItsHourlyLimitIsDroppedAndAnAnswerToAnApprovalNeverCounts() {
        publishProcess(admin);
        var t = service(Map.of("telegram", fake));
        t.create(admin, "chat", Trigger.Kind.POLL, "inbox", Map.of("source", "telegram", "approvals", true, "rateLimit", Map.of("perHour", 2)));
        assertEquals(1, tickWith(t, msg(7, "one")));
        assertEquals(1, tickWith(t, msg(7, "two")));
        assertEquals(0, tickWith(t, msg(7, "three")), "over the limit: dropped, no run");
        assertEquals(2, runCount());
        assertEquals(1, tickWith(t, msg(8, "other chat")), "another chat has its own allowance");
        assertEquals(0, tickWith(t, msg(7, "yes")), "an answer is never limited");
        assertEquals(1, api.listRuns(admin, "inbox", 100).stream().filter(r -> r.status() == RunStatus.COMPLETED).count());
        now = now.plusSeconds(3600);
        assertEquals(1, tickWith(t, msg(7, "four")), "the window has moved on");
    }

    @Test
    void theRateLimitSettingIsCheckedWhenCreated() {
        publishProcess(admin);
        var t = service(Map.of("telegram", fake));
        assertThrows(ApiException.class, () -> t.create(admin, "a", Trigger.Kind.POLL, "inbox", Map.of("source", "telegram", "rateLimit", Map.of("perHour", 0))));
        assertThrows(ApiException.class, () -> t.create(admin, "b", Trigger.Kind.POLL, "inbox", Map.of("source", "telegram", "rateLimit", 5)));
        assertThrows(ApiException.class, () -> t.create(admin, "c", Trigger.Kind.SCHEDULE, "inbox", Map.of("cron", "@daily", "rateLimit", Map.of("perHour", 5))));
    }

    @Test
    void answersDoNotUseUpTheAllowanceOfRealMessages() {
        publishProcess(admin);
        var t = service(Map.of("telegram", fake));
        t.create(admin, "chat", Trigger.Kind.POLL, "inbox", Map.of("source", "telegram", "approvals", true, "rateLimit", Map.of("perHour", 2)));
        assertEquals(1, tickWith(t, msg(7, "one")));
        assertEquals(0, tickWith(t, msg(7, "yes")));
        assertEquals(1, tickWith(t, msg(7, "two")), "the yes did not count");
        assertEquals(0, tickWith(t, msg(7, "three")), "two real messages were counted");
    }
}
