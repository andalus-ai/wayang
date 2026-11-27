package tech.kayys.wayang.adapter.aqli;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AqliQueryMentionModelTest {
    @Test
    void parsesExactMentionPredictionsAndUsesAqliAuthHeader() {
        AqliInferenceClient client = new AqliInferenceClient(URI.create("http://localhost:9131"),
                "test-key", Duration.ofSeconds(3), 100_000,
                (uri, headers, body, timeout) -> {
                    assertEquals("/v1/chat/completions", uri.getPath());
                    assertEquals("test-key", headers.get("X-API-Key"));
                    assertTrue(body.contains("Acme Corp"));
                    return new AqliInferenceClient.Response(200,
                            """
                            {"choices":[{"message":{"content":"{\\"mentions\\":[{\\"entityType\\":\\"Organization\\",\\"surfaceText\\":\\"Acme Corp\\",\\"startOffset\\":8,\\"endOffset\\":17,\\"confidence\\":0.97}]}"} }]}
                            """);
                });
        AqliQueryMentionModel model = new AqliQueryMentionModel(client, "local-model");

        var result = model.extract("Compare Acme Corp", 10);

        assertEquals(1, result.size());
        assertEquals("Acme Corp", result.get(0).surfaceText());
        assertEquals(8, result.get(0).startOffset());
    }

    @Test
    void rejectsNonJsonAssistantOutputSoTheCoreCanUseItsFallback() {
        AqliInferenceClient client = new AqliInferenceClient(URI.create("http://localhost:9131"),
                "test-key", Duration.ofSeconds(3), 100_000,
                (uri, headers, body, timeout) -> new AqliInferenceClient.Response(200,
                        """
                        {"choices":[{"message":{"content":"Here are the entities: Acme Corp"}}]}
                        """));
        AqliQueryMentionModel model = new AqliQueryMentionModel(client, "local-model");

        assertThrows(IllegalStateException.class, () -> model.extract("Acme Corp", 10));
    }
}
