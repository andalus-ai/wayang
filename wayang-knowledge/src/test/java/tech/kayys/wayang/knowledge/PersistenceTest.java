package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.rag.Document;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.runtime.ServiceHandler;

/** A "restart" is a second extension instance over the same directory: nothing is shared in memory. */
class PersistenceTest {

    private static KnowledgeExtension boot(Path dir) {
        return new KnowledgeExtension(new FileStore(dir));
    }

    private static Map<String, Object> cfg(String tenant, String name) {
        return Map.of("knowledge", name, "wayang.tenant", tenant, "chunking", Map.of("type", "clause", "maxChars", 800));
    }

    private static String top(KnowledgeExtension e, Map<String, Object> config, String q) throws Exception {
        List<?> hits = (List<?>) e.connectors().get("rag-search").call(config, Map.of("query", q)).get("hits");
        return hits.isEmpty() ? "" : String.valueOf(((Map<?, ?>) hits.get(0)).get("source"));
    }

    @Test
    void knowledgeSurvivesARestart() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        KnowledgeExtension before = boot(dir);
        before.connectors().get("rag-ingest").call(cfg("acme", "law"), Map.of("documents", List.of(
                Map.of("fileName", "msa.md", "text", "Article 1 Scope\nServices.\n\nArticle 2 Termination\nThirty days written notice.", "metadata", Map.of("kind", "legal")),
                Map.of("fileName", "faq.md", "text", "Lunch is at noon."))));
        KnowledgeExtension after = boot(dir);
        assertEquals("msa.md", top(after, Map.of("knowledge", "law", "wayang.tenant", "acme"), "termination notice"));
        // chunking came from the stored settings: the clause chunker kept Article 2 together
        Map<?, ?> hit = (Map<?, ?>) ((List<?>) after.connectors().get("rag-search")
                .call(Map.of("knowledge", "law", "wayang.tenant", "acme", "retrieval", Map.of("method", "bm25", "topK", 1)), Map.of("query", "termination", "filter", Map.of("kind", "legal"))).get("hits")).get(0);
        assertTrue(String.valueOf(hit.get("text")).contains("Article 2"));
    }

    @Test
    void replacingAndForgettingSurviveARestartToo() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        KnowledgeExtension a = boot(dir);
        Map<String, Object> c = cfg("t", "k");
        a.connectors().get("rag-ingest").call(c, Map.of("documents", List.of(Map.of("id", "d1", "fileName", "one.txt", "text", "alpha original"),
                Map.of("id", "d2", "fileName", "two.txt", "text", "beta stays"))));
        a.connectors().get("rag-ingest").call(c, Map.of("documents", List.of(Map.of("id", "d1", "fileName", "one.txt", "text", "alpha replaced"))));
        assertEquals(1, a.connectors().get("rag-forget").call(c, Map.of("documentIds", List.of("d2", "missing"))).get("removed"));
        KnowledgeExtension b = boot(dir);
        Map<String, Object> sc = Map.of("knowledge", "k", "wayang.tenant", "t");
        assertEquals("one.txt", top(b, sc, "replaced"));
        assertEquals("", top(b, sc, "original"));
        assertEquals("", top(b, sc, "beta"));
        assertEquals(0, b.connectors().get("rag-forget").call(sc, Map.of()).get("removed")); // missing input: reported, not fatal
    }

    @Test
    void tenantsStaySeparateOnDisk() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        KnowledgeExtension a = boot(dir);
        a.connectors().get("rag-ingest").call(cfg("acme", "shared"), Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "acme only"))));
        KnowledgeExtension b = boot(dir);
        assertThrows(ServiceHandler.ConnectorException.class, () -> b.connectors().get("rag-search").call(Map.of("knowledge", "shared", "wayang.tenant", "globex"), Map.of("query", "acme")));
        assertEquals("a.txt", top(b, Map.of("knowledge", "shared", "wayang.tenant", "acme"), "acme"));
    }

    @Test
    void persistFalseKeepsABaseInMemoryOnly() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        KnowledgeExtension a = boot(dir);
        a.connectors().get("rag-ingest").call(Map.of("knowledge", "scratch", "persist", false), Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "temporary notes"))));
        assertEquals("a.txt", top(a, Map.of("knowledge", "scratch"), "temporary"));
        assertThrows(ServiceHandler.ConnectorException.class, () -> boot(dir).connectors().get("rag-search").call(Map.of("knowledge", "scratch"), Map.of("query", "temporary")));
    }

    @Test
    void anEmbedderKeyIsNeverWrittenToDiskAndTheNodeSuppliesItAfterARestart() throws Exception {
        java.util.List<String> auth = new java.util.concurrent.CopyOnWriteArrayList<>();
        com.sun.net.httpserver.HttpServer server = com.sun.net.httpserver.HttpServer.create(new java.net.InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/embeddings", ex -> {
            String body = new String(ex.getRequestBody().readAllBytes(), java.nio.charset.StandardCharsets.UTF_8);
            auth.add(String.valueOf(ex.getRequestHeaders().getFirst("Authorization")));
            int n = 1; // every request in this test carries exactly one text
            StringBuilder sb = new StringBuilder("{\"data\":[");
            for (int i = 0; i < Math.max(1, n); i++) {
                sb.append(i == 0 ? "" : ",").append("{\"index\":").append(i).append(",\"embedding\":[1,0,0,0,0,0,0,0]}");
            }
            byte[] out = sb.append("]}").toString().getBytes(java.nio.charset.StandardCharsets.UTF_8);
            ex.sendResponseHeaders(200, out.length);
            ex.getResponseBody().write(out);
            ex.close();
        });
        server.start();
        try {
            Path dir = Files.createTempDirectory("wayang-kb");
            String base = "http://127.0.0.1:" + server.getAddress().getPort() + "/v1";
            Map<String, Object> emb = Map.of("type", "openai", "model", "m", "dimensions", 8, "baseUrl", base, "apiKey", "sk-never-on-disk");
            KnowledgeExtension first = new KnowledgeExtension(new FileStore(dir), new tech.kayys.wayang.connectors.UrlGuard(true, java.util.Set.of()));
            first.connectors().get("rag-ingest").call(Map.of("knowledge", "vec", "embedder", emb, "retrieval", Map.of("method", "vector")),
                    Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "hello vectors"))));
            try (Stream<Path> s = Files.walk(dir)) {
                for (Path p : s.filter(Files::isRegularFile).collect(Collectors.toList())) {
                    assertFalse(new String(Files.readAllBytes(p), java.nio.charset.StandardCharsets.ISO_8859_1).contains("sk-never-on-disk"), p.toString());
                }
            }
            // after a restart the base is rebuilt using the key the searching node supplies
            auth.clear();
            KnowledgeExtension second = new KnowledgeExtension(new FileStore(dir), new tech.kayys.wayang.connectors.UrlGuard(true, java.util.Set.of()));
            assertEquals("a.txt", top(second, Map.of("knowledge", "vec", "embedder", emb, "retrieval", Map.of("method", "vector")), "anything"));
            assertTrue(auth.stream().allMatch("Bearer sk-never-on-disk"::equals) && !auth.isEmpty(), auth.toString());
        } finally {
            server.stop(0);
        }
    }

    @Test
    void aFailedFirstIngestLeavesNothingStuckAndSettingsCanBeCorrected() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        KnowledgeExtension e = boot(dir);
        Map<String, Object> bad = Map.of("knowledge", "fix", "embedder", Map.of("type", "openai", "model", "m", "dimensions", 8, "baseUrl", "http://127.0.0.1:9/v1"));
        Map<String, Object> docs = Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "hello world")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> e.connectors().get("rag-ingest").call(bad, docs));
        try (Stream<Path> s = Files.walk(dir)) {
            assertFalse(s.anyMatch(p -> p.toString().endsWith("definition.json")), "nothing stored for a failed ingest");
        }
        // the same name now works with corrected settings, in this process and after a restart
        assertEquals(1, e.connectors().get("rag-ingest").call(Map.of("knowledge", "fix"), docs).get("ingested"));
        assertEquals("a.txt", top(boot(dir), Map.of("knowledge", "fix"), "hello"));
    }

    @Test
    void hashingEmbedderBasesAreRebuiltAfterARestart() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        Map<String, Object> c = Map.of("knowledge", "h", "embedder", Map.of("type", "hashing", "dimensions", 256), "retrieval", Map.of("method", "hybrid", "topK", 2));
        boot(dir).connectors().get("rag-ingest").call(c, Map.of("documents", List.of(Map.of("fileName", "refund.txt", "text", "Refunds are issued within five business days."))));
        KnowledgeExtension b = boot(dir);
        assertEquals("refund.txt", top(b, Map.of("knowledge", "h", "retrieval", Map.of("method", "hybrid")), "refunds issued"));
        // a search that states conflicting settings is still refused after the restart
        assertThrows(ServiceHandler.ConnectorException.class, () -> b.connectors().get("rag-search")
                .call(Map.of("knowledge", "h", "chunking", Map.of("type", "fixed", "size", 50, "overlap", 5)), Map.of("query", "refunds")));
    }

    @Test
    void aDamagedStoredDocumentIsAnErrorNotASilentLoss() throws Exception {
        Path dir = Files.createTempDirectory("wayang-kb");
        Map<String, Object> c = Map.of("knowledge", "dmg");
        boot(dir).connectors().get("rag-ingest").call(c, Map.of("documents", List.of(Map.of("fileName", "a.txt", "text", "fine"))));
        try (Stream<Path> s = Files.walk(dir)) {
            Path doc = s.filter(p -> p.toString().contains("docs") && p.toString().endsWith(".json")).findFirst().orElseThrow();
            Files.writeString(doc, "{not json");
        }
        ServiceHandler.ConnectorException e = assertThrows(ServiceHandler.ConnectorException.class, () -> boot(dir).connectors().get("rag-search").call(c, Map.of("query", "fine")));
        assertTrue(e.getMessage().contains("damaged"), e.getMessage());
    }

    @Test
    void storedDocumentsPerBaseAreBounded() throws Exception {
        KnowledgePersistence p = new KnowledgePersistence(Files.createTempDirectory("wayang-kb"), 2000);
        p.saveDefinition("t", "small", Map.of(), null);
        p.saveDocument("t", "small", new Document("a", "a", "x".repeat(1200), Map.of()), 0);
        assertThrows(RagException.class, () -> p.saveDocument("t", "small", new Document("b", "b", "x".repeat(1200), Map.of()), 0));
        p.saveDocument("t", "small", new Document("a", "a", "y".repeat(1200), Map.of()), 0); // replacing the same document is allowed
    }
}
