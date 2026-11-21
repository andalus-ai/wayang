package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.sun.net.httpserver.HttpServer;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.connectors.UrlGuard;

/** A restart must not pay the embedding endpoint again for text it already embedded. */
class VectorPersistenceTest {

    private HttpServer server;
    private String base;
    private final List<String> texts = new CopyOnWriteArrayList<>();
    private final List<String> auth = new CopyOnWriteArrayList<>();

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
            texts.addAll(inputs);
            StringBuilder sb = new StringBuilder("{\"data\":[");
            for (int i = 0; i < inputs.size(); i++) {
                String t = inputs.get(i).toLowerCase();
                sb.append(i == 0 ? "" : ",").append("{\"index\":").append(i).append(",\"embedding\":[")
                        .append(t.contains("cat") || t.contains("kitten") || t.contains("feline") ? 1 : 0).append(",")
                        .append(t.contains("car") || t.contains("engine") ? 1 : 0).append(",0.01,0.01,0.01,0.01,0.01,0.01]}");
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

    private static KnowledgeExtension boot(Path dir) {
        return new KnowledgeExtension(new FileStore(dir), new UrlGuard(true, java.util.Set.of()));
    }

    private Map<String, Object> cfg(String key, String model) {
        Map<String, Object> emb = new java.util.LinkedHashMap<>(Map.of("type", "openai", "model", model, "dimensions", 8, "baseUrl", base));
        if (key != null) {
            emb.put("apiKey", key);
        }
        return Map.of("knowledge", "pets", "wayang.tenant", "t", "embedder", emb, "retrieval", Map.of("method", "vector", "topK", 1));
    }

    private static String top(KnowledgeExtension e, Map<String, Object> c, String q) throws Exception {
        return String.valueOf(((Map<?, ?>) ((List<?>) e.connectors().get("rag-search").call(c, Map.of("query", q)).get("hits")).get(0)).get("source"));
    }

    private static void ingest(KnowledgeExtension e, Map<String, Object> c, Map<String, Object>... docs) throws Exception {
        e.connectors().get("rag-ingest").call(c, Map.of("documents", List.of(docs)));
    }

    @SuppressWarnings("unchecked")
    @Test
    void aRestartRebuildsTheBaseWithoutEmbeddingAgain() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        ingest(boot(dir), cfg("sk-1", "m"), Map.of("fileName", "a.txt", "text", "The kitten sleeps."), Map.of("fileName", "b.txt", "text", "The engine of the car."));
        assertEquals(2, texts.size());
        texts.clear();
        auth.clear();

        KnowledgeExtension after = boot(dir);
        assertEquals("a.txt", top(after, cfg("sk-1", "m"), "feline"));
        assertEquals(List.of("feline"), texts, "only the question was sent; both documents came back from disk");
    }

    @Test
    @SuppressWarnings("unchecked")
    void rebuildingDoesNotNeedTheKeyAndTheKeyIsNeverOnDisk() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        ingest(boot(dir), cfg("sk-very-secret", "m"), Map.of("fileName", "a.txt", "text", "The kitten sleeps."));
        texts.clear();
        auth.clear();
        // the first node after a restart is a search that supplies the key: replay of the documents makes no request, the question one
        assertEquals("a.txt", top(boot(dir), cfg("sk-very-secret", "m"), "feline"));
        assertEquals(List.of("Bearer sk-very-secret"), auth, "one request, for the question");
        try (Stream<Path> files = Files.walk(dir)) {
            for (Path p : files.filter(Files::isRegularFile).toList()) {
                assertFalse(new String(Files.readAllBytes(p), StandardCharsets.ISO_8859_1).contains("sk-very-secret"), p.toString());
            }
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void anEditedDocumentOnlyEmbedsItsChangedChunks() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        KnowledgeExtension e = boot(dir);
        Map<String, Object> c = new java.util.LinkedHashMap<>(cfg("sk", "m"));
        c.put("chunking", Map.of("type", "paragraph", "maxChars", 100));
        ingest(e, c, Map.of("id", "d", "fileName", "a.txt", "text", "The kitten sleeps all afternoon on the warm sofa by the window in the living room today.\n\nThe engine of the old truck is very loud when it starts on a cold morning in the garage.\n\nA third paragraph that talks about nothing in particular, only there to fill the space ok."));
        assertEquals(3, texts.size());
        texts.clear();
        ingest(e, c, Map.of("id", "d", "fileName", "a.txt", "text", "The kitten sleeps all afternoon on the warm sofa by the window in the living room today.\n\nThe engine of the old truck is quiet now after the mechanic fixed it in the garage today.\n\nA third paragraph that talks about nothing in particular, only there to fill the space ok."));
        assertEquals(1, texts.size(), texts.toString());
        assertTrue(texts.get(0).contains("quiet"));
    }

    @Test
    @SuppressWarnings("unchecked")
    void anotherModelOrSizeDoesNotReuseOldVectors() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        ingest(boot(dir), cfg("sk", "model-a"), Map.of("fileName", "a.txt", "text", "The kitten sleeps."));
        texts.clear();
        // a new base name with another model on the same disk location is a different cache scope
        Map<String, Object> other = new java.util.LinkedHashMap<>(cfg("sk", "model-b"));
        other.put("knowledge", "pets2");
        ingest(boot(dir), other, Map.of("fileName", "a.txt", "text", "The kitten sleeps."));
        assertEquals(1, texts.size(), "a different base and model embeds for itself");
    }

    @Test
    @SuppressWarnings("unchecked")
    void persistFalseWritesNoVectorsAndADamagedCacheIsRebuilt() throws Exception {
        Path off = Files.createTempDirectory("wayang-kb");
        Map<String, Object> c = new java.util.LinkedHashMap<>(cfg("sk", "m"));
        c.put("persist", false);
        ingest(boot(off), c, Map.of("fileName", "a.txt", "text", "The kitten sleeps."));
        try (Stream<Path> files = Files.walk(off)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().equals("vectors.bin")).count());
        }

        Path dir = Files.createTempDirectory("wayang-kb");
        ingest(boot(dir), cfg("sk", "m"), Map.of("fileName", "a.txt", "text", "The kitten sleeps."));
        Path vf;
        try (Stream<Path> files = Files.walk(dir)) {
            vf = files.filter(p -> p.getFileName().toString().equals("vectors.bin")).findFirst().orElseThrow();
        }
        Files.write(vf, "damaged".getBytes());
        texts.clear();
        assertEquals("a.txt", top(boot(dir), cfg("sk", "m"), "feline"), "a damaged cache costs a re-embed, not an error");
        assertEquals(2, texts.size(), "the document again and the question");
    }

    @Test
    void freeLocalEmbeddersAreNotCached() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        ingest(boot(dir), Map.of("knowledge", "h", "wayang.tenant", "t", "embedder", Map.of("type", "hashing", "dimensions", 64)), Map.of("fileName", "a.txt", "text", "plain text"));
        try (Stream<Path> files = Files.walk(dir)) {
            assertEquals(0, files.filter(p -> p.getFileName().toString().equals("vectors.bin")).count());
        }
    }
}
