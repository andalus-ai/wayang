package tech.kayys.wayang.api.definition;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.agent.AgentSpec;

class AgentToolCheckTest {

    private static List<ValidationReport.Item> check(AgentSpec.Tool... tools) {
        return AgentToolCheck.check(new AgentSpec("a", null, "m", null, null, List.of(tools), 5, 60, null, null, null));
    }

    private static AgentSpec.Tool tool(String type, Map<String, Object> cfg) {
        return new AgentSpec.Tool("t", type, null, cfg);
    }

    @Test
    void cleanToolsPass() {
        assertTrue(check(tool("http", Map.of("url", "https://x.test", "maxTokens", 5, "tokenizer", "x",
                "headers", Map.of("Authorization", "Bearer ${secret:API_KEY}", "X-Api-Key", "${secret:file.KEY}"),
                "parameters", Map.of("type", "object", "properties", Map.of("password", Map.of("type", "string")))))).isEmpty());
    }

    @Test
    void missingTypeAndBadParametersAreErrors() {
        assertEquals(1, check(tool(" ", Map.of())).size());
        assertEquals(1, check(tool(null, Map.of())).size());
        assertEquals(1, check(tool("http", Map.of("parameters", "nope"))).size());
    }

    @Test
    void literalCredentialsAreRejectedWithoutEchoingThem() {
        var items = check(tool("http", Map.of("headers", Map.of("Authorization", "Bearer sk-live-123456"), "apiKey", "hunter2")));
        assertEquals(2, items.size());
        for (var i : items) {
            assertEquals("ERROR", i.severity());
            assertFalse(i.message().contains("sk-live") || i.message().contains("hunter2"), i.message());
        }
        assertTrue(items.stream().anyMatch(i -> i.message().contains("config.headers.Authorization")));
        assertEquals(1, check(tool("http", Map.of("token", "${secret:A} extra"))).size()); // a literal glued to a reference
        assertEquals(1, check(tool("http", Map.of("items", List.of(Map.of("password", "pw"))))).size());
    }

    @Test
    void malformedReferencesAreRejected() {
        assertEquals(1, check(tool("http", Map.of("url", "https://x/${secret:}"))).size());
        assertEquals(1, check(tool("http", Map.of("url", "https://x/${env:HOME}"))).size());
        assertEquals(1, check(tool("http", Map.of("url", "${secret:A b}"))).size());
        assertTrue(check(tool("http", Map.of("url", "https://x/${config:HOST}/v1"))).isEmpty());
    }
}
