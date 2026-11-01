package tech.kayys.wayang.embed;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.net.URI;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.json.Json;

class DoctorTest {

    private static final List<String> SECRETS = List.of("TELEGRAM_TOKEN", "llm.openai.api-key");

    @SuppressWarnings("unchecked")
    private static ReleaseBundle bundle(Map<String, Object> telegramConfig) {
        Map<String, Object> b = (Map<String, Object>) Json.read(EmbedTest.bundle(true, SECRETS));
        List<Object> defs = new ArrayList<>((List<Object>) b.get("definitions"));
        Map<String, Object> agent = new LinkedHashMap<>();
        agent.put("id", "replier");
        agent.put("name", "Replier");
        agent.put("model", "openai/gpt-4o-mini");
        agent.put("systemPrompt", "Be brief");
        agent.put("inputTemplate", "{{ event.text }}");
        agent.put("maxSteps", 3);
        agent.put("timeoutSeconds", 30);
        defs.add(Map.of("kind", "AGENT", "id", "replier", "publishedVersion", 1, "document", agent));
        b.put("definitions", defs);
        Map<String, Object> trig = new LinkedHashMap<>();
        trig.put("id", "chat");
        trig.put("kind", "POLL");
        trig.put("processId", "hello");
        trig.put("enabled", true);
        Map<String, Object> cfg = new LinkedHashMap<>(Map.of("source", "telegram", "botToken", "${secret:TELEGRAM_TOKEN}"));
        cfg.putAll(telegramConfig);
        trig.put("config", cfg);
        b.put("triggers", List.of(trig));
        return ReleaseBundle.parse(Json.write(b));
    }

    private static final Map<String, Object> GOOD = Map.of("allowedChatIds", List.of(1), "rateLimit", Map.of("perHour", 60), "approvals", true);

    private final Map<String, String> secretValues = new HashMap<>(Map.of("GREET_KEY", "greet-secret-1", "TELEGRAM_TOKEN", "123:tg-token-value",
            "llm.openai.api-key", "sk-live-key-value"));

    private EmbeddedRuntime rt(ReleaseBundle b) {
        return new EmbeddedRuntime(b, Map.of(), null, ReleaseSecrets.secrets(null, k -> k.startsWith("WAYANG_SECRET_") ? secretValues.get(
                secretValues.keySet().stream().filter(n -> ("WAYANG_SECRET_" + n.toUpperCase().replaceAll("[^A-Z0-9]", "_")).equals(k)).findFirst().orElse("")) : null),
                ReleaseSecrets.config(b.config(), k -> null), Clock.systemUTC());
    }

    private final List<String> requested = new ArrayList<>();
    private Doctor.Reply modelReply = new Doctor.Reply(200, "{\"data\":[]}");
    private Doctor.Reply telegramReply = new Doctor.Reply(200, "{\"ok\": true,\"result\":{}}");
    private IOException failWith;

    private final Doctor.Http http = (method, url, headers) -> {
        requested.add(method + " " + url.getHost());
        if (failWith != null) {
            throw failWith;
        }
        return url.getHost().equals("api.telegram.org") ? telegramReply : modelReply;
    };

    private List<Doctor.Finding> doctor(ReleaseBundle b, Map<String, String> env, boolean live) {
        return new Doctor(rt(b), env::get, (t, n) -> secretValues.get(n), http).run(live);
    }

    private static boolean has(List<Doctor.Finding> f, Doctor.Level level, String idStart) {
        return f.stream().anyMatch(x -> x.level() == level && x.id().startsWith(idStart));
    }

    @Test
    void aSoundSetupHasNothingToFix() throws Exception {
        Path state = Files.createTempDirectory("wayang-doctor");
        var f = doctor(bundle(GOOD), Map.of("WAYANG_STATE_DIR", state.toString(), "WAYANG_API_KEY", "k", "WAYANG_LLM_DAILY_TOKENS", "100000"), false);
        assertTrue(f.stream().noneMatch(x -> x.level() == Doctor.Level.FAIL || x.level() == Doctor.Level.WARN), Doctor.text(f));
        assertTrue(has(f, Doctor.Level.OK, "secrets") && has(f, Doctor.Level.OK, "state") && has(f, Doctor.Level.OK, "api-key") && has(f, Doctor.Level.OK, "spend"), Doctor.text(f));
        assertEquals(0, Doctor.exitCode(f));
        assertTrue(requested.isEmpty(), "without --live nothing is sent anywhere");
        try (var files = Files.list(state)) {
            assertEquals(0L, files.count(), "the writability test leaves nothing behind");
        }
    }

    @Test
    void everyRiskIsNamedWithoutRevealingAnySecret() throws Exception {
        secretValues.remove("TELEGRAM_TOKEN");
        Path blocker = Files.createTempFile("wayang-doctor", ".file");
        var f = doctor(bundle(Map.of()), Map.of("WAYANG_STATE_DIR", blocker.resolve("sub").toString(), "WAYANG_LLM_OPENAI_URL", "http://models.example.com/v1",
                "WAYANG_LLM_DAILY_TOKENS", "many"), false);
        String text = Doctor.text(f);
        assertTrue(has(f, Doctor.Level.FAIL, "secret:TELEGRAM_TOKEN"), text);
        assertTrue(has(f, Doctor.Level.FAIL, "state"), "an unwritable state directory: " + text);
        assertTrue(has(f, Doctor.Level.WARN, "api-key"), text);
        assertTrue(has(f, Doctor.Level.WARN, "model:openai"), "plain http to a remote host: " + text);
        assertTrue(has(f, Doctor.Level.FAIL, "spend"), "a daily limit that is not a number: " + text);
        assertTrue(f.stream().filter(x -> x.id().equals("trigger:chat")).map(Doctor.Finding::level).toList().contains(Doctor.Level.FAIL), "no allow-list: " + text);
        assertTrue(f.stream().filter(x -> x.id().equals("trigger:chat") && x.level() == Doctor.Level.WARN).count() == 2, "no rate limit, no approvals: " + text);
        assertEquals(1, Doctor.exitCode(f));
        for (String secret : secretValues.values()) {
            assertFalse(text.contains(secret), "no secret value in the report");
        }
        assertTrue(text.contains("problem(s) to fix"));
    }

    @Test
    void withoutAnyTokenCapTheSpendIsAWarning() {
        var f = doctor(bundle(GOOD), Map.of("WAYANG_API_KEY", "k", "WAYANG_STATE_DIR", System.getProperty("java.io.tmpdir")), false);
        assertTrue(has(f, Doctor.Level.WARN, "spend"), Doctor.text(f));
        assertEquals(0, Doctor.exitCode(f), "a warning does not fail the check");
        assertTrue(Doctor.text(f).contains("Works, with"));
    }

    @Test
    void liveProbesTellWhetherTheKeysAreAcceptedAndNeverPrintThem() {
        var env = Map.of("WAYANG_STATE_DIR", System.getProperty("java.io.tmpdir"), "WAYANG_API_KEY", "k", "WAYANG_LLM_DAILY_TOKENS", "1000");
        var f = doctor(bundle(GOOD), env, true);
        assertEquals(List.of("GET api.openai.com", "GET api.telegram.org"), requested);
        assertTrue(has(f, Doctor.Level.OK, "live:model:openai") && has(f, Doctor.Level.OK, "live:telegram:TELEGRAM_TOKEN"), Doctor.text(f));

        modelReply = new Doctor.Reply(401, "{\"error\":\"bad key sk-live-key-value\"}");
        telegramReply = new Doctor.Reply(200, "{\"ok\":false,\"description\":\"Unauthorized\"}");
        var bad = doctor(bundle(GOOD), env, true);
        assertTrue(Doctor.text(bad).contains("rejected the credentials (HTTP 401)"), Doctor.text(bad));
        assertTrue(Doctor.text(bad).contains("not with success"), Doctor.text(bad));

        failWith = new IOException("connect failed for https://api.telegram.org/bot123:tg-token-value/getMe");
        var down = doctor(bundle(GOOD), env, true);
        assertTrue(has(down, Doctor.Level.FAIL, "live:telegram") && has(down, Doctor.Level.FAIL, "live:model"), Doctor.text(down));
        for (var list : List.of(bad, down)) {
            for (String secret : secretValues.values()) {
                assertFalse(Doctor.text(list).contains(secret), "no secret value, even from an error message");
            }
        }
        assertEquals(1, Doctor.exitCode(down));
    }

    @Test
    void aMissingModelKeyIsReportedWithoutTryingTheNetwork() {
        secretValues.remove("llm.openai.api-key");
        var f = doctor(bundle(GOOD), Map.of("WAYANG_STATE_DIR", System.getProperty("java.io.tmpdir"), "WAYANG_API_KEY", "k", "WAYANG_LLM_DAILY_TOKENS", "1"), true);
        assertTrue(has(f, Doctor.Level.FAIL, "live:model:openai"), Doctor.text(f));
        assertFalse(requested.stream().anyMatch(r -> r.contains("openai")), "nothing was sent without a key");
    }

    @Test
    void theCommandPrintsTextOrJsonAndExitsNonZeroOnAFailure() throws Exception {
        var b = bundle(GOOD);
        var out = new ByteArrayOutputStream();
        var err = new ByteArrayOutputStream();
        var env = Map.of("WAYANG_STATE_DIR", System.getProperty("java.io.tmpdir"), "WAYANG_API_KEY", "k", "WAYANG_LLM_DAILY_TOKENS", "1000");
        int code = Cli.run(new String[] {"doctor", "--live"}, rt(b), null, env::get, (t, n) -> secretValues.get(n), http, new ByteArrayInputStream(new byte[0]), new PrintStream(out), new PrintStream(err));
        assertEquals(0, code, out.toString());
        assertTrue(out.toString().contains("[ ok ] live:model:openai") && out.toString().contains("Ready."), out.toString());
        out.reset();
        secretValues.remove("TELEGRAM_TOKEN");
        code = Cli.run(new String[] {"doctor", "--json"}, rt(bundle(GOOD)), null, env::get, null, http, new ByteArrayInputStream(new byte[0]), new PrintStream(out), new PrintStream(err));
        assertEquals(1, code);
        assertTrue(Json.read(out.toString().strip()) instanceof List<?> l && !l.isEmpty() && ((Map<?, ?>) l.get(0)).containsKey("level"), out.toString());
        assertEquals(64, Cli.run(new String[] {"doctor", "--nope"}, rt(b), null, env::get, null, http, new ByteArrayInputStream(new byte[0]), new PrintStream(out), new PrintStream(err)));
    }
}
