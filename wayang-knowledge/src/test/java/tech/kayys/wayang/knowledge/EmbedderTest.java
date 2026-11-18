package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;

/** A local fake of an OpenAI-compatible embeddings endpoint: "cat"-like words point one way, "car"-like words another, so ranking is semantic by construction. */
class EmbedderTest {

    private HttpServer server;
    private String base;
    private final List<String> auth = new CopyOnWriteArrayList<>();
    private final List<Integer> batchSizes = new CopyOnWriteArrayList<>();
    private volatile int dims = 8;

    @BeforeEach
    void start() throws Exception {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            List<String> inputs = new ArrayList<>();
            java.util.regex.Matcher m = java.util.regex.Pattern.compile("\"input\":\\[(.*?)\\]").matcher(body);
            if (m.find()) {
                for (String s : m.group(1).split("\",\"")) {
                    inputs.add(s.replace("\"", ""));
                }
            }
            batchSizes.add(inputs.size());
            StringBuilder sb = new StringBuilder("{\"data\":[");
            for (int i = 0; i < inputs.size(); i++) {
                String t = inputs.get(i).toLowerCase();
                boolean animal = t.contains("cat") || t.contains("kitten") || t.contains("feline");
                boolean vehicle = t.contains("car") || t.contains("automobile") || t.contains("engine");
                sb.append(i == 0 ? "" : ",").append("{\"index\":").append(i).append(",\"embedding\":[");
                for (int k = 0; k < dims; k++) {
                    double v = k == 0 ? (animal ? 1 : 0) : k == 1 ? (vehicle ? 1 : 0) : 0.01;
                    sb.append(k == 0 ? "" : ",").append(v);
                }
                sb.append("]}");
            }
            byte[] out = sb.append("]}").toString().getBytes(StandardCharsets.UTF_8);
            ex.getResponseHeaders().add("Content-Type", "application/json");
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private ServiceHandler.Connector connector(String name) {
        KnowledgeRegistry reg = new KnowledgeRegistry(new UrlGuard(true, java.util.Set.of()));
        return name.equals("ingest") ? new RagIngest(reg) : new RagSearch(reg);
    }

    @Test
    void vectorSearchFindsMeaningThatSharesNoWords() throws Exception {
        KnowledgeRegistry reg = new KnowledgeRegistry(new UrlGuard(true, java.util.Set.of()));
        RagIngest ingest = new RagIngest(reg);
        RagSearch search = new RagSearch(reg);
        Map<String, Object> cfg = Map.of("knowledge", "pets", "embedder", Map.of("type", "openai", "model", "fake-embed", "dimensions", 8, "baseUrl", base, "apiKey", "sk-test-123"),
                "retrieval", Map.of("method", "vector", "topK", 1));
        ingest.call(cfg, Map.of("documents", List.of(
                Map.of("fileName", "a.txt", "text", "The kitten sleeps on the sofa."),
                Map.of("fileName", "b.txt", "text", "The automobile needs a new engine."))));
        Map<String, Object> res = search.call(cfg, Map.of("query", "feline"));
        assertEquals("a.txt", ((Map<?, ?>) ((List<?>) res.get("hits")).get(0)).get("source"));
        assertEquals("b.txt", ((Map<?, ?>) ((List<?>) search.call(cfg, Map.of("query", "car")).get("hits")).get(0)).get("source"));
        assertTrue(auth.stream().allMatch("Bearer sk-test-123"::equals), auth.toString());
        assertFalse(res.toString().contains("sk-test-123"));
    }

    @Test
    void chunksAreSentInBatches() throws Exception {
        KnowledgeRegistry reg = new KnowledgeRegistry(new UrlGuard(true, java.util.Set.of()));
        Map<String, Object> cfg = Map.of("knowledge", "big", "chunking", Map.of("type", "fixed", "size", 100, "overlap", 0),
                "embedder", Map.of("type", "openai", "model", "fake-embed", "dimensions", 8, "baseUrl", base));
        new RagIngest(reg).call(cfg, Map.of("fileName", "long.txt", "text", "word ".repeat(1000)));
        assertTrue(batchSizes.size() >= 2 && batchSizes.stream().allMatch(n -> n <= OpenAiEmbedder.BATCH), batchSizes.toString());
    }

    @Test
    void aWrongVectorSizeFailsInsteadOfPoisoningTheIndex() throws Exception {
        dims = 4;
        ServiceHandler.Connector ingest = connector("ingest");
        ServiceHandler.ConnectorException e = assertThrows(ServiceHandler.ConnectorException.class, () -> ingest.call(
                Map.of("knowledge", "bad", "embedder", Map.of("type", "openai", "model", "m", "dimensions", 8, "baseUrl", base, "apiKey", "sk-secret-xyz")),
                Map.of("fileName", "a.txt", "text", "some text that is long enough")));
        assertFalse(e.getMessage().contains("sk-secret-xyz"));
    }

    @Test
    void embedderSettingsAreValidated() {
        ServiceHandler.Connector ingest = connector("ingest");
        Map<String, Object> doc = Map.of("fileName", "a.txt", "text", "x y");
        assertThrows(ServiceHandler.ConnectorException.class, () -> ingest.call(Map.of("knowledge", "v1", "embedder", Map.of("type", "openai", "dimensions", 8)), doc));
        assertThrows(ServiceHandler.ConnectorException.class, () -> ingest.call(Map.of("knowledge", "v2", "embedder", Map.of("type", "openai", "model", "m")), doc));
        assertThrows(ServiceHandler.ConnectorException.class, () -> ingest.call(Map.of("knowledge", "v3", "embedder", Map.of("type", "openai", "model", "m", "dimensions", 8, "key", "x")), doc));
        assertThrows(ServiceHandler.ConnectorException.class, () -> ingest.call(Map.of("knowledge", "v4", "embedder", Map.of("type", "openai", "model", "bad model!", "dimensions", 8)), doc));
    }

    @Test
    void privateEndpointsAreBlockedByDefault() {
        KnowledgeRegistry reg = new KnowledgeRegistry(new UrlGuard(false, java.util.Set.of()));
        assertThrows(ServiceHandler.ConnectorException.class, () -> new RagIngest(reg).call(
                Map.of("knowledge", "ssrf", "embedder", Map.of("type", "openai", "model", "m", "dimensions", 8, "baseUrl", base)),
                Map.of("fileName", "a.txt", "text", "some text")));
    }
}
