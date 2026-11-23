package tech.kayys.wayang.embed;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Clock;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.json.Json;

/** An agent released on its own: no workflow in the bundle, a chat endpoint instead. Against a local server that plays the model. */
class AgentChatTest {

    private static String bundle() {
        Map<String, Object> agent = new LinkedHashMap<>(Map.of("id", "bot", "name", "Bot", "model", "ollama/x", "systemPrompt", "Be brief.",
                "inputTemplate", "{{ event.text }}", "maxSteps", 2, "timeoutSeconds", 20));
        agent.put("options", Map.of("memory", Map.of("key", "{{ event.chatId }}", "turns", 3)));
        Map<String, Object> b = new LinkedHashMap<>();
        b.put("format", "wayang.workspace");
        b.put("formatVersion", 1);
        b.put("project", Map.of("name", "A"));
        b.put("definitions", List.of(Map.of("kind", "AGENT", "id", "bot", "publishedVersion", 1, "document", agent)));
        b.put("config", Map.of());
        b.put("requires", Map.of("secrets", List.of(), "externalSecrets", List.of(), "oauthConnections", List.of(), "config", List.of()));
        b.put("integrity", Map.of("sha256", "abc"));
        return Json.write(b);
    }

    private static HttpResponse<String> post(HttpClient c, int port, String path, String key, String body) throws Exception {
        var rb = HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + port + path)).header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(body));
        if (key != null) {
            rb.header("Authorization", "Bearer " + key);
        }
        return c.send(rb.build(), HttpResponse.BodyHandlers.ofString());
    }

    @Test
    void anAgentOnItsOwnAnswersOverHttpAndRemembersPerChat() throws Exception {
        List<List<?>> seen = new ArrayList<>();
        HttpServer llm = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        llm.createContext("/chat/completions", ex -> {
            @SuppressWarnings("unchecked")
            Map<String, Object> req = (Map<String, Object>) Json.read(new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8));
            seen.add((List<?>) req.get("messages"));
            byte[] out = Json.write(Map.of("choices", List.of(Map.of("message", Map.of("role", "assistant", "content", "answer " + seen.size()))))).getBytes();
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        llm.start();
        try {
            Map<String, String> env = Map.of("WAYANG_LLM_OLLAMA_URL", "http://127.0.0.1:" + llm.getAddress().getPort());
            var rt = new EmbeddedRuntime(ReleaseBundle.parse(bundle()), Map.of(), new LlmAgentInvoker(Map.of(), null, null, env::get), null, null,
                    Clock.systemUTC());
            assertEquals(java.util.Set.of("bot"), rt.agentIds());
            assertTrue(rt.processIds().isEmpty(), "the wrapping plan is not a process of the release");
            try (var s = new HttpService(rt, "sekret", new InetSocketAddress("127.0.0.1", 0), 4096).start()) {
                HttpClient c = HttpClient.newHttpClient();
                assertEquals(401, post(c, s.port(), "/v1/agents/bot/chat", null, "{\"message\":\"hi\"}").statusCode());
                var first = post(c, s.port(), "/v1/agents/bot/chat", "sekret", "{\"message\":\"my name is Dewi\",\"chatId\":\"A\"}");
                assertEquals(200, first.statusCode(), first.body());
                @SuppressWarnings("unchecked")
                Map<String, Object> r1 = (Map<String, Object>) Json.read(first.body());
                assertEquals("answer 1", r1.get("reply"));
                assertEquals("A", r1.get("chatId"));
                assertEquals(2, seen.get(0).size(), "system + the message, no history yet");
                post(c, s.port(), "/v1/agents/bot/chat", "sekret", "{\"message\":\"what is my name?\",\"chatId\":\"A\"}");
                assertEquals(4, seen.get(1).size(), "the same chat is told what was said: " + seen.get(1));
                post(c, s.port(), "/v1/agents/bot/chat", "sekret", "{\"message\":\"hello\",\"chatId\":\"B\"}");
                assertEquals(2, seen.get(2).size(), "another chat sees nothing of chat A");
                assertFalse(seen.get(2).toString().contains("Dewi"));

                assertEquals(404, post(c, s.port(), "/v1/agents/nope/chat", "sekret", "{\"message\":\"hi\"}").statusCode());
                assertEquals(400, post(c, s.port(), "/v1/agents/bot/chat", "sekret", "{}").statusCode());
                assertEquals(400, post(c, s.port(), "/v1/agents/bot/chat", "sekret", "{\"message\":\"\"}").statusCode());
                assertEquals(400, post(c, s.port(), "/v1/agents/bot/chat", "sekret", "{\"message\":\"x\",\"chatId\":\"a b/../\"}").statusCode());
                assertEquals(400, post(c, s.port(), "/v1/agents/bot/chat", "sekret", "{\"message\":5}").statusCode());
                var list = c.send(HttpRequest.newBuilder(URI.create("http://127.0.0.1:" + s.port() + "/v1/agents")).header("Authorization", "Bearer sekret").build(),
                        HttpResponse.BodyHandlers.ofString());
                assertTrue(list.body().contains("\"bot\""), list.body());
            }
        } finally {
            llm.stop(0);
        }
    }
}
