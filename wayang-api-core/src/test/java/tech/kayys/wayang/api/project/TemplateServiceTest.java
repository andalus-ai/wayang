package tech.kayys.wayang.api.project;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.config.ConfigService;
import tech.kayys.wayang.api.config.ConfigStore;
import tech.kayys.wayang.api.definition.DefinitionKind;
import tech.kayys.wayang.api.definition.DefinitionStore;
import tech.kayys.wayang.api.DefinitionSummary;
import tech.kayys.wayang.api.run.EventBus;
import tech.kayys.wayang.api.secret.InMemorySecretStore;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.run.ReferenceRunEngine;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.api.run.RunEngine;
import tech.kayys.wayang.api.run.RunStatus;
import tech.kayys.wayang.api.run.RunView;
import tech.kayys.wayang.api.secret.SecretCipher;
import tech.kayys.wayang.api.secret.SecretService;
import tech.kayys.wayang.api.trigger.Trigger;
import tech.kayys.wayang.api.trigger.TriggerService;
import tech.kayys.wayang.api.trigger.TriggerStore;
import tech.kayys.wayang.api.WayangApi;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.WaitRegistry;

class TemplateServiceTest {

    private final Clock clock = Clock.fixed(Instant.parse("2026-02-03T04:05:06Z"), ZoneOffset.UTC);
    private final WaitRegistry.InMemory waits = new WaitRegistry.InMemory();
    private final DefinitionStore store = new DefinitionStore.InMemory();
    private final ProjectStore projects = new ProjectStore.InMemory();
    private final EventBus bus = new EventBus();
    private final RunEngine engine = new ReferenceRunEngine(store, waits, Map.of(), (spec, in) -> Map.of(), bus, clock);
    private final WayangApi api = new WayangApi(store, projects, engine, waits, bus, clock);
    private final ConfigService config = new ConfigService(new ConfigStore.InMemory(), x -> projects.get(x[0], x[1]).isPresent(), (t, p) -> null, clock);
    private final SecretService secrets = new SecretService(new InMemorySecretStore(), new SecretCipher(Map.of("k1", new byte[32]), "k1"), clock);
    private final Poller fake = (c, cursor) -> new Poller.Result(List.of(), "c");
    private final TriggerService triggers = new TriggerService(new TriggerStore.InMemory(), api, Map.of("telegram", fake), tech.kayys.wayang.connectors.Connectors.webhookProviders(), (t, n) -> "x", clock);
    private final WorkspaceService ws = new WorkspaceService(api, triggers, config, secrets, null, clock);
    private final ReleaseService releases = new ReleaseService(ws, api, "0.0.1");

    private final TemplateService templates = new TemplateService(ws);

    private final Principal acme = new Principal("acme", "ann", Set.of(Role.ADMIN));
    private final Principal viewer = new Principal("acme", "vic", Set.of(Role.VIEWER));

    @Test
    void theTelegramAssistantIsCreatedPublishedAndWiredButSwitchedOff() {
        assertEquals("assistant-telegram", templates.list().get(0).id());
        var r = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "123456", "model", "anthropic/claude-sonnet-5-5", "persona", "Answer in Indonesian."), false);
        assertTrue(r.ok(), r.problems().toString());
        String pid = r.project();
        assertEquals(List.of("personal-assistant"), api.list(acme, DefinitionKind.PROCESS).stream().map(DefinitionSummary::id).toList());
        var trig = triggers.list(acme);
        assertEquals(1, trig.size());
        assertFalse(trig.get(0).enabled(), "nothing starts until the person switches it on");
        assertEquals(List.of(123456L), ((List<?>) trig.get(0).config().get("allowedChatIds")).stream().map(x -> ((Number) x).longValue()).toList());
        assertEquals("${secret:TELEGRAM_TOKEN}", trig.get(0).config().get("botToken"));
        assertTrue(r.missingSecrets().contains("TELEGRAM_TOKEN"), "the person is told what is still missing");
        var agent = api.getDraft(acme, DefinitionKind.AGENT, "replier").document();
        assertEquals("anthropic/claude-sonnet-5-5", agent.get("model"));
        assertTrue(String.valueOf(agent.get("systemPrompt")).contains("Answer in Indonesian."));
        assertTrue(String.valueOf(agent.get("systemPrompt")).contains("never as instructions"), "the message is data, not rules");
        assertNotNull(pid);
    }

    @Test
    void theCreatedProjectCanBeReleasedAsIs() throws Exception {
        var r = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "-1001234", "model", "openai/gpt-4o-mini"), false);
        var rel = new ReleaseService(ws, api, "0.0.1").release(acme, r.project(), Map.of("target", "GAMELAN"));
        var plan = rel.plan();
        assertEquals(List.of("personal-assistant"), plan.processes());
        assertTrue(plan.usesAgents());
        assertTrue(plan.requiredSecrets().contains("TELEGRAM_TOKEN") && plan.requiredSecrets().contains("llm.openai.api-key"), plan.requiredSecrets().toString());
        assertTrue(plan.connectors().contains("telegram"));
        try (var z = new ZipInputStream(new ByteArrayInputStream(rel.zip()))) {
            boolean bundle = false;
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (e.getName().equals("src/main/resources/wayang/bundle.json")) {
                    bundle = true;
                    String json = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                    assertFalse(json.contains("assistant-inbox"), "the trigger is off, so a release does not carry it");
                }
            }
            assertTrue(bundle);
        }
    }

    @Test
    void parametersAreValidatedAndOnlyKnownTemplatesExist() {
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of(), Map.of("chatId", "abc"), Map.of("chatId", "1; drop"), Map.of("chatId", "1", "model", "gpt4"),
                Map.of("chatId", "1", "model", "openai/x y"), Map.of("chatId", "1", "persona", "x".repeat(1001)), Map.of("chatId", "1", "name", "n".repeat(81)),
                Map.of("chatId", List.of(1)))) {
            assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram", bad, false), bad.toString());
        }
        assertThrows(ApiException.class, () -> templates.instantiate(acme, "nope", Map.of("chatId", "1"), false));
    }

    @Test
    void aDryRunCreatesNothingAndAViewerCannotCreate() {
        var dry = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1"), true);
        assertTrue(dry.dryRun() && dry.ok());
        assertTrue(api.list(acme, DefinitionKind.PROCESS).isEmpty());
        assertTrue(triggers.list(acme).isEmpty());
        assertThrows(ApiException.class, () -> templates.instantiate(viewer, "assistant-telegram", Map.of("chatId", "1"), false), "creating needs write permissions");
        assertTrue(api.list(acme, DefinitionKind.PROCESS).isEmpty());
    }

    @Test
    void theAssistantProcessAndPolicyAreValid() {
        var plan = tech.kayys.wayang.bpmn.ProcessCompiler.compile(TemplateService.process(false));
        assertEquals("personal-assistant", plan.processId());
        assertEquals("personal-assistant", tech.kayys.wayang.bpmn.ProcessCompiler.compile(TemplateService.process(true)).processId());
        assertNotNull(tech.kayys.wayang.dmn.DmnLoader.load(TemplateService.policy()));
        assertNotNull(tech.kayys.wayang.agent.AgentSpecLoader.fromMap(TemplateService.agent("openai/m", "")));
    }

    @Test
    void theSlackAssistantUsesASignedWebhookRestrictedToOneChannel() {
        assertEquals(4, templates.list().size());
        var r = templates.instantiate(acme, "assistant-slack", Map.of("channelId", "D0123ABCD", "model", "ollama/llama3.1"), false);
        assertTrue(r.ok(), r.problems().toString());
        var t = triggers.list(acme).get(0);
        assertEquals(Trigger.Kind.WEBHOOK, t.kind());
        assertFalse(t.enabled());
        assertEquals("slack", t.config().get("auth"));
        assertEquals(List.of("D0123ABCD"), t.config().get("allowedChannels"));
        assertTrue(r.missingSecrets().containsAll(List.of("SLACK_BOT_TOKEN", "SLACK_SIGNING_SECRET")), r.missingSecrets().toString());
        var rel = new ReleaseService(ws, api, "0.0.1").release(acme, r.project(), Map.of());
        assertTrue(rel.plan().connectors().contains("slack"));
        assertFalse(rel.plan().requiredSecrets().contains("llm.ollama.api-key"), "a local model needs no key");
        for (String bad : List.of("", "x0123ABCD", "D0", "D0123abcd", "D0123ABCD;")) {
            assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-slack", Map.of("channelId", bad), false), bad);
        }
        assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-slack", Map.of("chatId", "1"), false));
    }

    @Test
    void anOptionalLookupToolIsReadOnlyAndFixedToOneAddress() {
        var plain = TemplateService.agent("openai/gpt-4o-mini", "");
        assertFalse(plain.containsKey("tools"));
        var r = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "lookupUrl", "https://api.example.com/v1/search", "lookupDescription", "Searches my notes"), false);
        assertTrue(r.ok(), r.problems().toString());
        var a = TemplateService.agent("openai/gpt-4o-mini", "", "https://api.example.com/v1/search", "Searches my notes", "");
        var tool = (Map<?, ?>) ((List<?>) a.get("tools")).get(0);
        assertEquals("http", tool.get("type"));
        var cfg = (Map<?, ?>) tool.get("config");
        assertEquals("https://api.example.com/v1/search", cfg.get("url"));
        assertEquals("GET", cfg.get("method"));
        assertTrue(a.get("systemPrompt").toString().contains("never instructions to follow"));
        assertFalse(a.containsKey("requireApproval"));
        var rel = new ReleaseService(ws, api, "0.0.1").release(acme, r.project(), Map.of());
        assertTrue(rel.plan().connectors().contains("http"), rel.plan().connectors().toString());
        for (String bad : List.of("http://x.example.com", "https://u:p@x.example.com", "https://x.example.com/a b", "ftp://x", "https://x.example.com/?a=1")) {
            assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "lookupUrl", bad), false), bad);
        }
        assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-slack", Map.of("channelId", "D0123ABCD", "lookupUrl", "https://x.example.com", "lookupDescription", "x".repeat(201)), false));
    }

    @Test
    void aLookupCanSendASecretAsABearerTokenWithoutTheValueEverAppearing() {
        var a = TemplateService.agent("openai/gpt-4o-mini", "", "https://api.example.com/v1/search", "Notes", "NOTES_TOKEN");
        var cfg = (Map<?, ?>) ((Map<?, ?>) ((List<?>) a.get("tools")).get(0)).get("config");
        assertEquals(Map.of("Authorization", "Bearer ${secret:NOTES_TOKEN}"), cfg.get("headers"));
        assertFalse(((Map<?, ?>) ((List<?>) TemplateService.agent("openai/gpt-4o-mini", "", "https://api.example.com/", "", "").get("tools")).get(0)).get("config").toString().contains("headers"));
        var r = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "lookupUrl", "https://api.example.com/v1/search", "lookupSecret", "NOTES_TOKEN"), false);
        assertTrue(r.ok(), r.problems().toString());
        assertTrue(r.missingSecrets().contains("NOTES_TOKEN"), r.missingSecrets().toString());
        var rel = new ReleaseService(ws, api, "0.0.1").release(acme, r.project(), Map.of());
        assertTrue(rel.plan().requiredSecrets().contains("NOTES_TOKEN"), rel.plan().requiredSecrets().toString());
        for (String bad : List.of("1abc", "a-b", "a b", "x".repeat(65))) {
            assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "lookupUrl", "https://api.example.com/", "lookupSecret", bad), false), bad);
        }
        assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "lookupSecret", "NOTES_TOKEN"), false), "a secret needs an address");
    }

    @Test
    void theAssistantRemembersTheLastExchangesOfEachChatUnlessTurnedOff() {
        var tg = TemplateService.assistantTelegram("n", 1L, "openai/gpt-4o-mini", "", "", "", "", 10);
        var tgAgent = (Map<?, ?>) ((Map<?, ?>) ((List<?>) tg.get("definitions")).stream().filter(d -> "AGENT".equals(((Map<?, ?>) d).get("kind"))).findFirst().orElseThrow()).get("document");
        assertEquals(Map.of("key", "{{ event.chatId }}", "turns", 10), ((Map<?, ?>) tgAgent.get("options")).get("memory"));
        var sl = TemplateService.assistantSlack("n", "D0123ABCD", "openai/gpt-4o-mini", "", "", "", "", 4);
        var slAgent = (Map<?, ?>) ((Map<?, ?>) ((List<?>) sl.get("definitions")).stream().filter(d -> "AGENT".equals(((Map<?, ?>) d).get("kind"))).findFirst().orElseThrow()).get("document");
        assertEquals(Map.of("key", "{{ event.channel }}", "turns", 4), ((Map<?, ?>) slAgent.get("options")).get("memory"));
        assertFalse(((Map<?, ?>) TemplateService.agent("openai/gpt-4o-mini", "").get("options")).containsKey("memory"), "no memory unless asked");
        assertTrue(TemplateService.agent("m/x", "", "", "", "", "{{ k }}", 3).get("systemPrompt").toString().contains("Earlier messages"));
        var on = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1"), true);
        assertTrue(on.ok(), on.problems().toString());
        var off = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "memoryTurns", 0, "name", "Second"), true);
        assertTrue(off.ok(), off.problems().toString());
        for (Object bad : List.of(-1, 31, "many", "1.5")) {
            assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "memoryTurns", bad), false), String.valueOf(bad));
        }
    }

    @Test
    void theDailyBriefIsAScheduleThatWritesAndSendsToOnePlaceAndStartsSwitchedOff() {
        var r = templates.instantiate(acme, "daily-brief-telegram", Map.of("chatId", "123456", "topic", "the day ahead", "cron", "30 7 * * 1-5", "timezone", "Asia/Jakarta"), false);
        assertTrue(r.ok(), r.problems().toString());
        assertTrue(r.missingSecrets().contains("TELEGRAM_TOKEN"));
        var t = triggers.list(acme).get(0);
        assertEquals(Trigger.Kind.SCHEDULE, t.kind());
        assertFalse(t.enabled());
        assertEquals("30 7 * * 1-5", t.config().get("cron"));
        assertEquals("Asia/Jakarta", t.config().get("timezone"));
        assertEquals("123456", ((Map<?, ?>) t.config().get("input")).get("chatId"));
        var agent = api.getDraft(acme, DefinitionKind.AGENT, "writer").document();
        assertNull(agent.get("tools"), "no lookup, no tools");
        assertTrue(String.valueOf(agent.get("systemPrompt")).contains("cannot take actions"));
        assertEquals(List.of("daily-brief"), api.list(acme, DefinitionKind.PROCESS).stream().map(DefinitionSummary::id).toList());
    }

    @Test
    void theSlackDailyBriefAndItsParametersAreChecked() {
        var r = templates.instantiate(acme, "daily-brief-slack", Map.of("channelId", "D0123ABCD", "topic", "open tasks", "lookupUrl", "https://tasks.example.com/v1", "lookupSecret", "TASKS_KEY"), true);
        assertTrue(r.ok(), r.problems().toString());
        assertTrue(r.missingSecrets().contains("SLACK_BOT_TOKEN"));
        for (Map<String, Object> bad : List.of(Map.<String, Object>of("channelId", "D0123ABCD"), Map.<String, Object>of("channelId", "D0123ABCD", "topic", "x".repeat(301)),
                Map.<String, Object>of("channelId", "D0123ABCD", "topic", "x", "cron", "61 * * * *"), Map.<String, Object>of("channelId", "D0123ABCD", "topic", "x", "timezone", "Mars/X"),
                Map.<String, Object>of("channelId", "nope", "topic", "x"), Map.<String, Object>of("chatId", "1", "topic", "x"),
                Map.<String, Object>of("channelId", "D0123ABCD", "topic", "x", "lookupSecret", "K"))) {
            assertThrows(ApiException.class, () -> templates.instantiate(acme, "daily-brief-slack", bad, true), bad.toString());
        }
        assertThrows(ApiException.class, () -> templates.instantiate(viewer, "daily-brief-slack", Map.of("channelId", "D0123ABCD", "topic", "x"), false));
    }

    @Test
    void theAssistantAsksForApprovalInTheChatThenSendsOrDropsTheDraft() {
        var r = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "123456"), false);
        assertTrue(r.ok(), r.problems().toString());
        assertEquals(Boolean.TRUE, triggers.list(acme).get(0).config().get("approvals"), "answers come back through the same chat");
        List<Map<String, Object>> sent = new java.util.ArrayList<>();
        var connectors = Map.<String, tech.kayys.wayang.runtime.ServiceHandler.Connector>of("telegram", in -> {
            sent.add(in);
            return Map.of("ok", true);
        });
        var eng = new ReferenceRunEngine(store, waits, connectors, (spec, in) -> Map.of("output", "See https://example.com for details"), bus, clock);
        var api2 = new WayangApi(store, projects, eng, waits, bus, clock);
        var run = api2.startRun(acme, "personal-assistant", null, Map.of("event", Map.of("chatId", 123456L, "text", "what is that site?"), "trigger", "assistant-inbox"));
        assertEquals(RunStatus.WAITING, run.status(), String.valueOf(run.error()));
        assertEquals(1, sent.size(), "the question went to the chat before waiting");
        assertEquals(123456L, ((Number) sent.get(0).get("chatId")).longValue());
        assertTrue(String.valueOf(sent.get(0).get("text")).contains("Answer yes to send it or no to drop it"), String.valueOf(sent.get(0)));
        assertTrue(String.valueOf(sent.get(0).get("text")).contains("See https://example.com"), "the person sees exactly what would be sent");
        api2.completeTask(acme, run.waiting().get(0).token(), Map.of("approved", true));
        assertEquals(2, sent.size(), "approved: the draft is sent");
        assertEquals("See https://example.com for details", sent.get(1).get("text"));
        var second = api2.startRun(acme, "personal-assistant", null, Map.of("event", Map.of("chatId", 123456L, "text", "again"), "trigger", "assistant-inbox"));
        api2.completeTask(acme, second.waiting().get(0).token(), Map.of("approved", false));
        assertEquals(3, sent.size(), "refused: only the question went out, nothing more");
        assertEquals(RunStatus.COMPLETED, api2.getRun(acme, second.id()).status());
    }

    private static LlmAgentScript script(String eventText) {
        return new LlmAgentScript(eventText);
    }

    /** A scripted model: asks for calendar_create once, then answers. */
    private static final class LlmAgentScript implements tech.kayys.wayang.embed.LlmAgentInvoker.Transport {
        final List<String> bodies = new java.util.ArrayList<>();

        LlmAgentScript(String ignored) {
        }

        @Override
        public tech.kayys.wayang.embed.LlmAgentInvoker.Response post(java.net.URI url, Map<String, String> headers, String body, java.time.Duration timeout) {
            bodies.add(body);
            Map<String, Object> msg = bodies.size() == 1
                    ? Map.of("role", "assistant", "tool_calls", List.of(Map.of("id", "c1", "type", "function", "function", Map.of("name", "calendar_create",
                            "arguments", "{\"summary\":\"Lunch\",\"start\":\"2026-11-02T12:00:00+07:00\",\"end\":\"2026-11-02T13:00:00+07:00\"}"))))
                    : Map.of("role", "assistant", "content", "Lunch is waiting for your yes.");
            return new tech.kayys.wayang.embed.LlmAgentInvoker.Response(200, tech.kayys.wayang.runtime.json.Json.write(Map.of("choices", List.of(Map.of("message", msg)))));
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void theCalendarAssistantProposesAnEventAndCreatesItOnlyAfterTheYes() throws Exception {
        var r = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "123456", "calendar", "true", "calendarConnection", "work"), false);
        assertTrue(r.ok(), r.problems().toString());
        var rel = new ReleaseService(ws, api, "0.0.1").release(acme, r.project(), Map.of("target", "STANDALONE"));
        assertTrue(rel.plan().connectors().contains("gcal") && rel.plan().connectors().contains("telegram"), rel.plan().connectors().toString());
        assertTrue(rel.plan().requiredSecrets().contains("oauth.work"), rel.plan().requiredSecrets().toString());
        String json = null;
        try (var z = new ZipInputStream(new ByteArrayInputStream(rel.zip()))) {
            for (ZipEntry e = z.getNextEntry(); e != null; e = z.getNextEntry()) {
                if (e.getName().equals("src/main/resources/wayang/bundle.json")) {
                    json = new String(z.readAllBytes(), StandardCharsets.UTF_8);
                }
            }
        }
        assertNotNull(json);
        assertFalse(json.contains("token-work"), "a bundle never carries secret values");

        List<Map<String, Object>> sent = new java.util.ArrayList<>();
        List<Map<String, Object>> gcalConfigs = new java.util.ArrayList<>();
        List<Map<String, Object>> gcalCalls = new java.util.ArrayList<>();
        Map<String, tech.kayys.wayang.runtime.ServiceHandler.Connector> connectors = Map.of(
                "telegram", in -> {
                    sent.add(in);
                    return Map.of("ok", true);
                },
                "gcal", new tech.kayys.wayang.runtime.ServiceHandler.Connector() {
                    @Override
                    public Map<String, Object> call(Map<String, Object> in) {
                        throw new IllegalStateException("needs configuration");
                    }

                    @Override
                    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> in) {
                        gcalConfigs.add(config);
                        gcalCalls.add(in);
                        return Map.of("id", "ev1");
                    }
                });
        tech.kayys.wayang.runtime.SecretResolver sec = (t, n) -> n.equals("oauth.work") ? "token-work" : n.equals("llm.openai.api-key") ? "sk-test" : n.equals("TELEGRAM_TOKEN") ? "tg" : null;
        for (boolean approve : new boolean[] {true, false}) {
            sent.clear();
            gcalCalls.clear();
            var model = script("x");
            var invoker = new tech.kayys.wayang.embed.LlmAgentInvoker(connectors, sec, null, k -> null, model);
            var rt = new tech.kayys.wayang.embed.EmbeddedRuntime(tech.kayys.wayang.embed.ReleaseBundle.parse(json), connectors, invoker, sec, null, clock);
            var run = rt.start("personal-assistant", Map.of("event", Map.of("chatId", 123456L, "text", "lunch tomorrow at noon"), "trigger", "assistant-inbox"));
            assertEquals("WAITING", run.status(), String.valueOf(run.error()));
            assertTrue(gcalCalls.isEmpty(), "nothing is created before the yes");
            assertEquals(1, sent.size());
            String ask = String.valueOf(sent.get(0).get("text"));
            assertTrue(ask.contains("Lunch is waiting for your yes.") && ask.contains("calendar_create") && ask.contains("Lunch"), ask);
            assertFalse(String.valueOf(run.variables()).contains("token-work"), "the run holds references only");
            assertTrue(rt.completeWait(String.valueOf(run.waiting().get(0).get("token")), Map.of("approved", approve)));
            if (approve) {
                assertEquals(1, gcalCalls.size(), "created after the yes");
                assertEquals("Lunch", gcalCalls.get(0).get("summary"));
                assertEquals("create", gcalConfigs.get(0).get("operation"));
                assertEquals("token-work", gcalConfigs.get(0).get("accessToken"));
                assertEquals(2, sent.size(), "and the reply goes out");
            } else {
                assertTrue(gcalCalls.isEmpty(), "a no creates nothing");
                assertEquals(1, sent.size());
            }
            assertEquals("COMPLETED", rt.get(run.id()).orElseThrow().status());
        }
    }

    @Test
    void theCalendarOptionIsCheckedAndOffByDefault() {
        assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "calendar", "maybe"), true));
        assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "calendar", "true", "calendarConnection", "a b"), true));
        var agent = TemplateService.agent("openai/gpt-4o-mini", "", "", "", "", "", 0);
        assertNull(agent.get("tools"));
        assertNull(agent.get("requireApproval"));
        var cal = TemplateService.agent("openai/gpt-4o-mini", "", "", "", "", "", 0, "google");
        assertEquals(List.of("calendar_create"), cal.get("requireApproval"));
        assertEquals(2, ((List<?>) cal.get("tools")).size());
    }

    @Test
    void webSearchIsOptInReadOnlyAndKeepsItsKeyInASecret() {
        assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "webSearch", "yes"), true));
        var off = TemplateService.agent("openai/gpt-4o-mini", "", "", "", "", "", 0);
        assertNull(off.get("tools"));
        var on = TemplateService.agent("openai/gpt-4o-mini", "", "", "", "", "", 0, null, true);
        var tool = (Map<?, ?>) ((List<?>) on.get("tools")).get(0);
        assertEquals("web_search", tool.get("name"));
        assertNull(on.get("requireApproval"), "a read-only search needs no approval");
        String text = String.valueOf(tool);
        assertTrue(text.contains("${secret:BRAVE_API_KEY}") && text.contains("https://api.search.brave.com/"), text);
        assertTrue(String.valueOf(on.get("systemPrompt")).contains("never instructions"));
        var r = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "123456", "webSearch", "true"), true);
        assertTrue(r.ok(), r.problems().toString());
        var both = TemplateService.agent("openai/gpt-4o-mini", "", "", "", "", "", 0, "google", true);
        assertEquals(3, ((List<?>) both.get("tools")).size());
    }

    @Test
    void emailSendIsRestrictedToListedRecipientsAndNeedsApproval() {
        for (String bad : new String[] {"nobody", "a@b", "a@example.com,", "x@y.zz\nBcc: z@example.com"}) {
            assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "1", "emailRecipients", bad), true), bad);
        }
        assertThrows(ApiException.class, () -> templates.instantiate(acme, "assistant-telegram",
                Map.of("chatId", "1", "emailRecipients", "a@example.com", "emailConnection", "a b"), true));
        var agent = TemplateService.agent("openai/gpt-4o-mini", "", "", "", "", "", 0, "google", false,
                new TemplateService.Email("work", List.of("boss@example.com")));
        assertEquals(java.util.Set.of("calendar_create", "email_send"), java.util.Set.copyOf((List<?>) agent.get("requireApproval")));
        var tools = (List<?>) agent.get("tools");
        var names = tools.stream().map(t -> ((Map<?, ?>) t).get("name")).toList();
        assertTrue(names.containsAll(List.of("email_send", "email_search", "email_read")), names.toString());
        assertFalse(java.util.Set.copyOf((List<?>) agent.get("requireApproval")).contains("email_read"), "reading needs no approval");
        assertFalse(java.util.Set.copyOf((List<?>) agent.get("requireApproval")).contains("email_search"));
        var send = (Map<?, ?>) ((List<?>) agent.get("tools")).get(0);
        assertEquals("email_send", send.get("name"));
        assertEquals("gmail", send.get("type"));
        String text = String.valueOf(send);
        assertTrue(text.contains("${secret:oauth.work}") && text.contains("allowedRecipients=[boss@example.com]"), text);
        assertTrue(String.valueOf(agent.get("systemPrompt")).contains("email_send"));
        var r = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "123456", "emailRecipients", "Boss@Example.com, ann@example.org"), true);
        assertTrue(r.ok(), r.problems().toString());
        assertNull(TemplateService.agent("openai/gpt-4o-mini", "", "", "", "", "", 0).get("requireApproval"));
    }

    @Test
    void theAssistantsCarryGuardrailsByDefault() {
        var agent = TemplateService.agent("openai/gpt-4o-mini", "", "", "", "", "", 0);
        var opts = (Map<?, ?>) agent.get("options");
        assertEquals(30000, opts.get("maxTotalTokens"));
        assertEquals(10, opts.get("maxToolCalls"));
        assertNull(opts.get("memory"));
        var t = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "123456"), false);
        assertTrue(t.ok(), t.problems().toString());
        assertEquals(Map.of("perHour", 60), triggers.list(acme).get(0).config().get("rateLimit"));
    }

    @Test
    void aForgetMessageClearsTheChatsMemoryAndTheAssistantSaysSo() {
        var r = templates.instantiate(acme, "assistant-telegram", Map.of("chatId", "123456", "memoryTurns", "5"), false);
        assertTrue(r.ok(), r.problems().toString());
        List<Map<String, Object>> sent = new java.util.ArrayList<>();
        var connectors = Map.<String, tech.kayys.wayang.runtime.ServiceHandler.Connector>of("telegram", in -> {
            sent.add(in);
            return Map.of("ok", true);
        });
        List<List<?>> histories = new java.util.ArrayList<>();
        var eng = new ReferenceRunEngine(store, waits, connectors, (spec, in) -> {
            histories.add((List<?>) in.get("history"));
            return Map.of("output", "fine");
        }, bus, clock).withMemory(new tech.kayys.wayang.agent.ConversationMemory.InMemory(100));
        var api2 = new WayangApi(store, projects, eng, waits, bus, clock);
        java.util.function.Function<String, RunView> say = text -> api2.startRun(acme, "personal-assistant", null,
                Map.of("event", Map.of("chatId", 123456L, "text", text), "trigger", "assistant-inbox"));
        say.apply("remember: my cat is Momo");
        say.apply("what is my cat called?");
        assertEquals(2, histories.get(1).size(), "the second message came with the first exchange");
        int draftsBefore = histories.size();
        say.apply("/forget");
        assertEquals(draftsBefore, histories.size(), "the agent was not asked");
        assertEquals("Done. I have forgotten this conversation.", sent.get(sent.size() - 1).get("text"));
        say.apply("and now?");
        assertTrue(histories.get(histories.size() - 1).isEmpty(), "nothing is remembered any more");
        say.apply("forget me");
        assertEquals("Done. I have forgotten this conversation.", sent.get(sent.size() - 1).get("text"));
        say.apply("/forget");
        assertEquals("There was nothing to forget.", sent.get(sent.size() - 1).get("text"));
    }

    @Test
    void templatesNeedTheAgentFeature() {
        var none = new TemplateService(ws).withProfile(tech.kayys.wayang.runtime.Profile.INTEGRATION);
        assertTrue(none.list().isEmpty());
        assertThrows(ApiException.class, () -> none.instantiate(new Principal("acme", "ann", java.util.Set.of(Role.ADMIN)), "assistant-telegram",
                Map.of("chatId", "123"), true));
        assertEquals(4, new TemplateService(ws).withProfile(tech.kayys.wayang.runtime.Profile.AGENTIC).list().size());
    }
}
