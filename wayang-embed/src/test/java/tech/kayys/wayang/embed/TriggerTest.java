package tech.kayys.wayang.embed;

import static org.junit.jupiter.api.Assertions.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WebhookProvider;
import tech.kayys.wayang.runtime.json.Json;

class TriggerTest {

    /** A clock the test moves. */
    private static final class Tick extends Clock {
        Instant now = Instant.parse("2026-01-01T00:00:00Z");

        @Override
        public ZoneId getZone() {
            return ZoneId.of("UTC");
        }

        @Override
        public Clock withZone(ZoneId zone) {
            return this;
        }

        @Override
        public Instant instant() {
            return now;
        }
    }

    private final Map<String, ServiceHandler.Connector> connectors = Map.of("greet", new ServiceHandler.Connector() {
        @Override
        public Map<String, Object> call(Map<String, Object> in) {
            return Map.of("text", "hi");
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) {
            return Map.of("text", "hi");
        }
    });

    private static Map<String, Object> trig(String id, String kind, String process, Map<String, Object> config, boolean enabled) {
        Map<String, Object> t = new LinkedHashMap<>();
        t.put("id", id);
        t.put("kind", kind);
        t.put("processId", process);
        t.put("enabled", enabled);
        t.put("config", config);
        return t;
    }

    @SuppressWarnings("unchecked")
    private static String withTriggers(List<Map<String, Object>> triggers) {
        Map<String, Object> b = (Map<String, Object>) Json.read(EmbedTest.bundle(true, List.of("GREET_KEY")));
        b.put("triggers", triggers);
        return Json.write(b);
    }

    private static final List<Map<String, Object>> STANDARD = List.of(
            trig("hook", "WEBHOOK", "simple", Map.of(), true),
            trig("gh", "WEBHOOK", "simple", Map.of("auth", "fakeprov", "token", "${secret:PROV_TOKEN}"), true),
            trig("feed", "POLL", "simple", Map.of("source", "fakepoll", "everySeconds", 10, "apiKey", "${secret:POLL_KEY}"), true),
            trig("off", "POLL", "simple", Map.of("source", "nothere"), false));

    private final WebhookProvider provider = new WebhookProvider() {
        @Override
        public void verify(Map<String, Object> config, Map<String, String> headers, byte[] body, Instant now) throws Rejected {
            if (!String.valueOf(config.get("token")).equals(headers.get("x-sig"))) {
                throw new Rejected("bad signature");
            }
        }

        @Override
        public Optional<String> handshake(Map<String, Object> config, Map<String, String> query, Map<String, Object> json) {
            return json == null && query.containsKey("challenge") ? Optional.of(query.get("challenge")) : Optional.empty();
        }

        @Override
        @SuppressWarnings("unchecked")
        public List<Event> events(Map<String, Object> config, Map<String, Object> json) {
            List<Event> out = new ArrayList<>();
            for (Object o : (List<Object>) json.getOrDefault("events", List.of())) {
                Map<String, Object> e = (Map<String, Object>) o;
                out.add(new Event(String.valueOf(e.get("id")), e));
            }
            return out;
        }
    };

    private final List<String> cursorsSeen = new ArrayList<>();
    private final List<Map<String, Object>> toEmit = new ArrayList<>();
    private RuntimeException failWith;

    private final Poller poller = (config, cursor) -> {
        cursorsSeen.add(cursor);
        if (failWith != null) {
            throw failWith;
        }
        List<Map<String, Object>> ev = List.copyOf(toEmit);
        toEmit.clear();
        return new Poller.Result(ev, "c" + (cursorsSeen.size()));
    };

    private EmbeddedRuntime rt(ReleaseBundle b, Map<String, String> env, Tick clock) {
        return new EmbeddedRuntime(b, connectors, null, ReleaseSecrets.secrets(null, env::get), ReleaseSecrets.config(b.config(), env::get), clock);
    }

    private ReleaseTriggers triggers(ReleaseBundle b, EmbeddedRuntime rt, Map<String, String> env, Tick clock, Path state) {
        return new ReleaseTriggers(b, rt, ReleaseSecrets.secrets(null, env::get), Map.of("fakepoll", poller), Map.of("fakeprov", provider), clock, state);
    }

    private static final Map<String, String> ENV = Map.of("WAYANG_SECRET_GREET_KEY", "k", "WAYANG_SECRET_TRIGGER_HOOK", "hook-secret",
            "WAYANG_SECRET_PROV_TOKEN", "prov-token", "WAYANG_SECRET_POLL_KEY", "poll-key-value");

    private static Map<String, String> h(String k, String v) {
        return Map.of(k, v);
    }

    @Test
    void theBundleCarriesEnabledTriggersAndNeedsTheWebhookSecretFromTheEnvironment() {
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(STANDARD));
        assertEquals(List.of("hook", "gh", "feed"), b.triggers().stream().map(ReleaseBundle.Trigger::id).toList(), "a switched-off trigger is dropped");
        assertTrue(b.requiredSecrets().contains("trigger.hook"));
        assertFalse(b.requiredSecrets().contains("trigger.gh"), "a provider-signed webhook has no shared secret");
        assertEquals("fakeprov", b.triggers().get(1).provider());
        assertEquals("trigger.hook", b.triggers().get(0).secretName());
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(withTriggers(List.of(trig("x", "WEBHOOK", "nope", Map.of(), true)))),
                "a trigger must point at a process of the release");
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(withTriggers(List.of(trig("a/b", "WEBHOOK", "simple", Map.of(), true)))));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(withTriggers(List.of(trig("x", "CRON", "simple", Map.of(), true)))));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(withTriggers(
                List.of(trig("x", "WEBHOOK", "simple", Map.of(), true), trig("x", "WEBHOOK", "simple", Map.of(), true)))), "ids are unique");
        assertTrue(rt(b, Map.of(), new Tick()).missingSecrets().contains("trigger.hook"), "readiness reports a missing webhook secret");
    }

    @Test
    void aSharedSecretWebhookStartsARunOnlyWithTheRightSecret() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(STANDARD));
        var rt = rt(b, ENV, c);
        var t = triggers(b, rt, ENV, c, null);
        byte[] body = "{\"order\":7}".getBytes(StandardCharsets.UTF_8);
        for (Map<String, String> bad : List.of(Map.<String, String>of(), h("X-Wayang-Secret", "nope"), h("X-Wayang-Secret", ""))) {
            var e = assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("hook", "POST", Map.of(), bad, body));
            assertEquals(401, e.status());
        }
        assertEquals(401, assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("unknown", "POST", Map.of(), h("X-Wayang-Secret", "hook-secret"), body)).status());
        assertEquals(401, assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("feed", "POST", Map.of(), h("X-Wayang-Secret", "hook-secret"), body)).status(),
                "a poll trigger is not a webhook");
        assertEquals(0, rt.list(null, 10).size(), "nothing ran");

        var ok = t.receive("hook", "POST", Map.of(), h("x-wayang-secret", "hook-secret"), body);
        assertEquals(202, ok.status());
        var run = rt.list("simple", 10);
        assertEquals(1, run.size());
        assertTrue(ok.body().contains(run.get(0).id()));
        assertEquals("hook", run.get(0).variables().get("trigger"));
        assertEquals(Map.of("order", 7L), asLongs(run.get(0).variables().get("event")));

        assertEquals(400, assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("hook", "POST", Map.of(), h("X-Wayang-Secret", "hook-secret"), "[1]".getBytes())).status());
        assertEquals(413, assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("hook", "POST", Map.of(), h("X-Wayang-Secret", "hook-secret"), new byte[(1 << 20) + 1])).status());
    }

    private static Map<String, Object> asLongs(Object o) {
        Map<String, Object> m = new HashMap<>();
        ((Map<?, ?>) o).forEach((k, v) -> m.put(String.valueOf(k), v instanceof Number n ? (Object) n.longValue() : v));
        return m;
    }

    @Test
    void aWebhookWhoseSecretIsNotConfiguredRefusesEverything() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(STANDARD));
        Map<String, String> env = Map.of("WAYANG_SECRET_GREET_KEY", "k");
        var t = triggers(b, rt(b, env, c), env, c, null);
        // even an empty or guessed header must not get in when there is no secret to compare with
        assertEquals(401, assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("hook", "POST", Map.of(), h("X-Wayang-Secret", ""), new byte[0])).status());
        assertEquals(401, assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("hook", "POST", Map.of(), Map.of(), new byte[0])).status());
        assertEquals(503, assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("gh", "POST", Map.of(), h("x-sig", "prov-token"), "{}".getBytes())).status(),
                "a provider webhook with a missing secret is not configured");
    }

    @Test
    void aProviderWebhookIsVerifiedHandshakenAndDeduplicatedByEventId() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(STANDARD));
        var rt = rt(b, ENV, c);
        var t = triggers(b, rt, ENV, c, null);
        String two = "{\"events\":[{\"id\":\"e1\"},{\"id\":\"e2\"}]}";
        assertEquals(401, assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("gh", "POST", Map.of(), h("x-sig", "wrong"), two.getBytes())).status());
        assertEquals(0, rt.list(null, 10).size());
        var r1 = t.receive("gh", "POST", Map.of(), h("X-Sig", "prov-token"), two.getBytes());
        assertEquals(200, r1.status());
        assertEquals("{\"runs\":2}", r1.body());
        var r2 = t.receive("gh", "POST", Map.of(), h("X-Sig", "prov-token"), "{\"events\":[{\"id\":\"e2\"},{\"id\":\"e3\"}]}".getBytes());
        assertEquals("{\"runs\":1}", r2.body(), "e2 was already handled");
        assertEquals(3, rt.list("simple", 10).size());
        var hs = t.receive("gh", "GET", Map.of("challenge", "abc"), Map.of(), null);
        assertEquals("abc", hs.body());
        assertFalse(hs.json());
        assertEquals(400, assertThrows(ReleaseTriggers.Refused.class, () -> t.receive("gh", "GET", Map.of(), Map.of(), null)).status());
    }

    @Test
    void pollingStartsRunsAdvancesTheCursorAndBacksOffOnFailureWithoutLeakingSecrets() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(STANDARD));
        var rt = rt(b, ENV, c);
        var t = triggers(b, rt, ENV, c, null);
        toEmit.add(Map.of("n", 1));
        toEmit.add(Map.of("n", 2));
        assertEquals(2, t.tick());
        assertEquals(java.util.Arrays.asList((String) null), cursorsSeen, "the first poll has no cursor");
        assertEquals(0, t.tick(), "not due yet");
        c.now = c.now.plusSeconds(10);
        assertEquals(0, t.tick());
        assertEquals(java.util.Arrays.asList(null, "c1"), cursorsSeen, "the next poll continues from the cursor");

        failWith = new IllegalStateException("upstream said poll-key-value is wrong");
        c.now = c.now.plusSeconds(10);
        t.tick();
        var st = t.status().stream().filter(m -> m.get("id").equals("feed")).findFirst().orElseThrow();
        assertEquals(1, st.get("consecutiveFailures"));
        assertEquals("upstream said *** is wrong", st.get("lastError"), "the secret value is masked");
        assertFalse(st.toString().contains("poll-key-value"));
        int calls = cursorsSeen.size();
        c.now = c.now.plusSeconds(10); // normal interval, but the backoff doubled it
        t.tick();
        assertEquals(calls, cursorsSeen.size());
        c.now = c.now.plusSeconds(10);
        t.tick();
        assertEquals(calls + 1, cursorsSeen.size(), "after the doubled wait it tries again");
        assertEquals("c2", cursorsSeen.get(cursorsSeen.size() - 1), "a failed poll did not move the cursor");
        failWith = null;
        c.now = c.now.plusSeconds(3600);
        toEmit.add(Map.of("n", 3));
        assertEquals(1, t.tick());
        assertEquals(0, ((Number) t.status().stream().filter(m -> m.get("id").equals("feed")).findFirst().orElseThrow().get("consecutiveFailures")).intValue());
    }

    @Test
    void aMissingPollSecretCountsAsAFailureAndNeverReachesThePoller() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(STANDARD));
        Map<String, String> env = Map.of("WAYANG_SECRET_GREET_KEY", "k");
        var t = triggers(b, rt(b, env, c), env, c, null);
        assertEquals(0, t.tick());
        assertTrue(cursorsSeen.isEmpty());
        assertEquals(1, t.status().stream().filter(m -> m.get("id").equals("feed")).findFirst().orElseThrow().get("consecutiveFailures"));
    }

    @Test
    void cursorsSurviveARestartWhenAStateDirectoryIsGiven() throws Exception {
        Path dir = Files.createTempDirectory("wayang-state");
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(STANDARD));
        var first = triggers(b, rt(b, ENV, c), ENV, c, dir);
        first.tick();
        assertEquals("c1", Files.readString(dir.resolve("cursor-feed")));
        cursorsSeen.clear();
        var second = triggers(b, rt(b, ENV, c), ENV, c, dir);
        second.tick();
        assertEquals(java.util.Arrays.asList("c1"), cursorsSeen, "the restarted release continues where the first stopped");
        try (var files = Files.list(dir)) {
            assertEquals(1L, files.count(), "no temporary files are left behind");
        }
    }

    @Test
    void aTriggerThatCannotWorkStopsTheReleaseAtStartup() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(STANDARD));
        var rt = rt(b, ENV, c);
        var sec = ReleaseSecrets.secrets(null, ENV::get);
        assertThrows(IllegalArgumentException.class, () -> new ReleaseTriggers(b, rt, sec, Map.of(), Map.of("fakeprov", provider), c, null), "no poller");
        assertThrows(IllegalArgumentException.class, () -> new ReleaseTriggers(b, rt, sec, Map.of("fakepoll", poller), Map.of(), c, null), "no provider");
        ReleaseBundle fast = ReleaseBundle.parse(withTriggers(List.of(trig("f", "POLL", "simple", Map.of("source", "fakepoll", "everySeconds", 1), true))));
        assertThrows(IllegalArgumentException.class, () -> new ReleaseTriggers(fast, rt, sec, Map.of("fakepoll", poller), Map.of(), c, null), "everySeconds < 5");
    }

    @Test
    void theServiceServesHooksWithoutTheApiKeyAndListsTriggersBehindIt() throws Exception {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(STANDARD));
        var rt = rt(b, ENV, c);
        try (var t = triggers(b, rt, ENV, c, null);
                var s = new HttpService(rt, "api-key", new InetSocketAddress("127.0.0.1", 0), 4096).withTriggers(t).start()) {
            HttpClient http = HttpClient.newHttpClient();
            String base = "http://127.0.0.1:" + s.port();
            var noSecret = http.send(HttpRequest.newBuilder(URI.create(base + "/hooks/hook")).POST(HttpRequest.BodyPublishers.ofString("{}")).build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(401, noSecret.statusCode());
            var apiKeyIsNotTheSecret = http.send(HttpRequest.newBuilder(URI.create(base + "/hooks/hook")).header("Authorization", "Bearer api-key")
                    .POST(HttpRequest.BodyPublishers.ofString("{}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(401, apiKeyIsNotTheSecret.statusCode());
            var ok = http.send(HttpRequest.newBuilder(URI.create(base + "/hooks/hook")).header("X-Wayang-Secret", "hook-secret")
                    .POST(HttpRequest.BodyPublishers.ofString("{\"a\":1}")).build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(202, ok.statusCode(), ok.body());
            assertEquals(1, rt.list("simple", 10).size());
            var challenge = http.send(HttpRequest.newBuilder(URI.create(base + "/hooks/gh?challenge=xyz")).GET().build(), HttpResponse.BodyHandlers.ofString());
            assertEquals(200, challenge.statusCode());
            assertEquals("xyz", challenge.body());
            assertTrue(challenge.headers().firstValue("Content-Type").orElse("").startsWith("text/plain"));
            assertEquals(404, http.send(HttpRequest.newBuilder(URI.create(base + "/hooks/a%2Fb")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            assertEquals(405, http.send(HttpRequest.newBuilder(URI.create(base + "/hooks/hook")).DELETE().build(), HttpResponse.BodyHandlers.ofString()).statusCode());

            assertEquals(401, http.send(HttpRequest.newBuilder(URI.create(base + "/v1/triggers")).GET().build(), HttpResponse.BodyHandlers.ofString()).statusCode());
            var list = http.send(HttpRequest.newBuilder(URI.create(base + "/v1/triggers")).header("Authorization", "Bearer api-key").GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(200, list.statusCode());
            assertTrue(list.body().contains("\"feed\"") && list.body().contains("\"hook\""));
            assertFalse(list.body().contains("hook-secret") || list.body().contains("POLL_KEY") || list.body().contains("prov-token"), "no configuration or secret in the listing");
        }
    }

    @Test
    void withoutTriggersTheHooksPathDoesNotExist() throws Exception {
        var rt = rt(ReleaseBundle.parse(EmbedTest.bundle(true, List.of("GREET_KEY"))), ENV, new Tick());
        try (var s = new HttpService(rt, "k", new InetSocketAddress("127.0.0.1", 0), 4096).start()) {
            var r = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/hooks/hook")).GET().build(),
                    HttpResponse.BodyHandlers.ofString());
            assertEquals(404, r.statusCode());
        }
    }

    @Test
    void readyzFollowsTheEnginesReadiness() throws Exception {
        var inner = rt(ReleaseBundle.parse(EmbedTest.bundle(true, List.of("GREET_KEY"))), ENV, new Tick());
        boolean[] ready = { false };
        ReleaseEngine gate = (ReleaseEngine) java.lang.reflect.Proxy.newProxyInstance(ReleaseEngine.class.getClassLoader(), new Class<?>[] { ReleaseEngine.class },
                (p, m, a) -> m.getName().equals("ready") ? (Object) ready[0] : m.invoke(inner, a));
        try (var s = new HttpService(gate, "k", new InetSocketAddress("127.0.0.1", 0), 4096).start()) {
            var c = HttpClient.newHttpClient();
            var req = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/readyz")).build();
            assertEquals(503, c.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
            ready[0] = true;
            assertEquals(200, c.send(req, HttpResponse.BodyHandlers.ofString()).statusCode());
        }
    }

    private static List<Map<String, Object>> schedule(Map<String, Object> cfg) {
        return List.of(trig("brief", "SCHEDULE", "simple", cfg, true));
    }

    @Test
    void aScheduleRunsOnceAtItsTimeAndSkipsARunMissedByMoreThanTheGrace() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(schedule(Map.of("cron", "0 8 * * *", "timezone", "Asia/Jakarta", "graceSeconds", 600, "input", Map.of("topic", "news")))));
        var t = triggers(b, rt(b, ENV, c), ENV, c, null); // starts counting at 2026-01-01T00:00Z = 07:00 in Jakarta
        assertEquals(0, t.tick(), "a first start does not run what is due later");
        c.now = Instant.parse("2026-01-01T01:00:00Z");
        assertEquals(1, t.tick());
        assertEquals(0, t.tick(), "once");
        c.now = Instant.parse("2026-01-01T01:00:30Z");
        assertEquals(0, t.tick());
        c.now = Instant.parse("2026-01-02T05:00:00Z"); // the release was down at 08:00: a morning brief at noon is skipped
        assertEquals(0, t.tick());
        var st = t.status().get(0);
        assertTrue(String.valueOf(st.get("lastError")).contains("Skipped the run scheduled for 2026-01-02T01:00:00Z"), String.valueOf(st));
        assertEquals("2026-01-03T01:00:00Z", st.get("nextRunAt"));
        c.now = Instant.parse("2026-01-03T01:00:00Z");
        assertEquals(1, t.tick());
        assertNull(t.status().get(0).get("lastError"));
    }

    @Test
    void aSchedulesLastRunSurvivesARestartAndABadCronStopsTheBundle() throws Exception {
        Path dir = Files.createTempDirectory("wayang-state");
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(schedule(Map.of("cron", "0 8 * * *"))));
        var first = triggers(b, rt(b, ENV, c), ENV, c, dir);
        c.now = Instant.parse("2026-01-01T08:00:00Z");
        assertEquals(1, first.tick());
        c.now = Instant.parse("2026-01-02T08:00:30Z"); // down over the next 08:00, back within its grace
        var second = triggers(b, rt(b, ENV, c), ENV, c, dir);
        assertEquals(1, second.tick(), "the restarted release remembers the last run and so runs the one it missed");
        assertEquals(0, second.tick(), "and only once");
        var fresh = triggers(b, rt(b, ENV, c), ENV, c, Files.createTempDirectory("wayang-state2"));
        assertEquals(0, fresh.tick(), "with no saved state it counts from its start");
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(withTriggers(schedule(Map.of("cron", "61 8 * * *")))));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(withTriggers(schedule(Map.of("cron", "0 8 * * *", "timezone", "Mars/X")))));
    }

    @Test
    void aYesOrNoFromTheSameChatAnswersTheOldestWaitingApprovalInARelease() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(List.of(
                trig("chat", "POLL", "hello", Map.of("source", "telegram", "everySeconds", 5, "approvals", true), true))));
        var rt = rt(b, ENV, c);
        var t = new ReleaseTriggers(b, rt, ReleaseSecrets.secrets(null, ENV::get), Map.of("telegram", poller), Map.of(), c, null);
        java.util.function.BiFunction<Long, String, Integer> say = (chat, text) -> {
            toEmit.clear();
            toEmit.add(Map.of("chatId", chat, "text", text));
            c.now = c.now.plusSeconds(10);
            return t.tick();
        };
        assertEquals(1, (int) say.apply(7L, "hello"));
        assertEquals(1, (int) say.apply(7L, "again"));
        assertEquals(1, (int) say.apply(8L, "yes"), "another chat's yes is an ordinary message");
        assertEquals(3, rt.list("hello", 10).size());
        assertEquals(0, (int) say.apply(7L, "yes"), "answers a waiting approval, starts nothing");
        assertEquals(3, rt.list("hello", 10).size());
        var done = rt.list("hello", 10).stream().filter(r -> r.status().equals("COMPLETED")).toList();
        assertEquals(1, done.size());
        assertEquals("hello", ((Map<?, ?>) done.get(0).variables().get("event")).get("text"), "the oldest one");
        assertTrue(String.valueOf(done.get(0).variables()).contains("approved=true"), String.valueOf(done.get(0).variables()));
        assertEquals(0, (int) say.apply(7L, "No."));
        assertTrue(rt.list("hello", 10).stream().anyMatch(r -> String.valueOf(r.variables()).contains("approved=false")));
        assertEquals(1, (int) say.apply(7L, "yes"), "nothing of chat 7 waits any more");
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(withTriggers(List.of(trig("x", "POLL", "hello", Map.of("source", "rss", "approvals", true), true)))));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(withTriggers(List.of(trig("x", "WEBHOOK", "hello", Map.of("approvals", true), true)))));
    }

    @Test
    void aThumbsPressIsNotAMessageForTheProcessInARelease() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(List.of(
                trig("chat", "POLL", "hello", Map.of("source", "telegram", "everySeconds", 5, "ratings", true), true))));
        var rt = rt(b, ENV, c);
        var t = new ReleaseTriggers(b, rt, ReleaseSecrets.secrets(null, ENV::get), Map.of("telegram", poller), Map.of(), c, null);
        toEmit.clear();
        toEmit.add(Map.of("kind", "feedback", "rating", "up", "chatId", 7L, "fromId", 5L));
        c.now = c.now.plusSeconds(10);
        assertEquals(0, t.tick(), "a release has no feedback store: the press is dropped, not run");
        assertEquals(0, rt.list("hello", 10).size());
        toEmit.clear();
        toEmit.add(Map.of("chatId", 7L, "text", "hello"));
        c.now = c.now.plusSeconds(10);
        assertEquals(1, t.tick(), "ordinary messages still run");
    }

    @Test
    void aChatOverItsHourlyLimitIsDroppedInAReleaseToo() {
        Tick c = new Tick();
        ReleaseBundle b = ReleaseBundle.parse(withTriggers(List.of(
                trig("chat", "POLL", "hello", Map.of("source", "telegram", "everySeconds", 5, "approvals", true, "rateLimit", Map.of("perHour", 2)), true))));
        var rt = rt(b, ENV, c);
        var t = new ReleaseTriggers(b, rt, ReleaseSecrets.secrets(null, ENV::get), Map.of("telegram", poller), Map.of(), c, null);
        java.util.function.BiFunction<Long, String, Integer> say = (chat, text) -> {
            toEmit.clear();
            toEmit.add(Map.of("chatId", chat, "text", text));
            c.now = c.now.plusSeconds(10);
            return t.tick();
        };
        assertEquals(1, (int) say.apply(7L, "one"));
        assertEquals(1, (int) say.apply(7L, "two"));
        assertEquals(0, (int) say.apply(7L, "three"));
        assertEquals(1, (int) say.apply(8L, "elsewhere"));
        assertEquals(3, rt.list("hello", 10).size());
        assertEquals(0, (int) say.apply(7L, "yes"), "an answer is never limited");
        c.now = c.now.plusSeconds(3600);
        assertEquals(1, (int) say.apply(7L, "four"));
        assertThrows(IllegalArgumentException.class, () -> ReleaseBundle.parse(withTriggers(List.of(
                trig("x", "POLL", "hello", Map.of("source", "telegram", "rateLimit", Map.of("perHour", 0)), true)))));
    }
}
