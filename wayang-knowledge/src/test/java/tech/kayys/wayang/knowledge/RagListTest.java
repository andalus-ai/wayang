package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RagListTest {

    private static Map<String, Object> cfg(String tenant) {
        return Map.of("knowledge", "k", "wayang.tenant", tenant);
    }

    private static Map<String, Object> doc(String id, String text, Object ttl) {
        Map<String, Object> m = new java.util.LinkedHashMap<>(Map.of("id", id, "fileName", id + ".txt", "text", text));
        if (ttl != null) {
            m.put("ttlDays", ttl);
        }
        return m;
    }

    @Test
    void listsIdsAndExpiryButNeverText() throws Exception {
        KnowledgeExtension e = new KnowledgeExtension(new FileStore(Files.createTempDirectory("wayang-list"), java.time.Clock.systemUTC(), 0, 0));
        e.connectors().get("rag-ingest").call(cfg("t"), Map.of("documents", List.of(doc("a", "secret-body one", 3), doc("b", "secret-body two", null))));
        Map<String, Object> out = e.connectors().get("rag-list").call(cfg("t"), Map.of());
        assertEquals(2, out.get("count"));
        List<?> docs = (List<?>) out.get("documents");
        assertEquals("a", ((Map<?, ?>) docs.get(0)).get("id"));
        assertTrue(((Map<?, ?>) docs.get(0)).containsKey("expiresAt"));
        assertFalse(((Map<?, ?>) docs.get(1)).containsKey("expiresAt"));
        assertFalse(out.toString().contains("secret-body"));
    }

    @Test
    void missingBaseIsEmptyAndTenantsAreSeparate() throws Exception {
        KnowledgeExtension e = new KnowledgeExtension(new FileStore(Files.createTempDirectory("wayang-list"), java.time.Clock.systemUTC(), 0, 0));
        assertEquals(0, e.connectors().get("rag-list").call(cfg("t"), Map.of()).get("count"));
        e.connectors().get("rag-ingest").call(cfg("t"), Map.of("documents", List.of(doc("a", "x y z", null))));
        assertEquals(0, e.connectors().get("rag-list").call(cfg("u"), Map.of()).get("count"));
        assertEquals(1, e.connectors().get("rag-list").call(cfg("t"), Map.of()).get("count"));
    }
}
