package tech.kayys.wayang.adapter.aqli;

import static org.junit.jupiter.api.Assertions.*;

import java.net.URI;
import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.Test;

class AqliEmbedderTest {
    @Test
    void supportsBatchEmbeddingsAndEnforcesConfiguredDimensions() {
        AqliInferenceClient client = new AqliInferenceClient(URI.create("http://localhost:9131"),
                "key", Duration.ofSeconds(3), 100_000,
                (uri, headers, body, timeout) -> {
                    assertEquals("/v1/embeddings", uri.getPath());
                    assertTrue(body.contains("Text 1"));
                    return new AqliInferenceClient.Response(200,
                            """
                            {"data":[{"embedding":[0.1,0.2,0.3]},{"embedding":[0.4,0.5,0.6]}]}
                            """);
                });
        AqliEmbedder embedder = new AqliEmbedder(client, "embedding-model", 3);

        List<float[]> vectors = embedder.embedAll(List.of("Text 1", "Text 2"));

        assertEquals(2, vectors.size());
        assertEquals(3, vectors.get(0).length);
        assertEquals("aqli:embedding-model:3", embedder.id());
    }

    @Test
    void rejectsUnexpectedEmbeddingDimensions() {
        AqliInferenceClient client = new AqliInferenceClient(URI.create("http://localhost:9131"),
                "key", Duration.ofSeconds(3), 100_000,
                (uri, headers, body, timeout) -> new AqliInferenceClient.Response(200,
                        """
                        {"data":[{"embedding":[0.1,0.2]}]}
                        """));
        AqliEmbedder embedder = new AqliEmbedder(client, "embedding-model", 3);

        assertThrows(IllegalStateException.class, () -> embedder.embed("Text"));
    }
}
