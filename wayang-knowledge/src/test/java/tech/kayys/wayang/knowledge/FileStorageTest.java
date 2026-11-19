package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;

class FileStorageTest {

    private static KnowledgeExtension ext(Path dir) {
        return new KnowledgeExtension(new FileStore(dir));
    }

    private static Map<String, Object> cfg(String tenant) {
        return Map.of("wayang.tenant", tenant);
    }

    @Test
    void putGetListDeleteRoundTrip() throws Exception {
        Path dir = Files.createTempDirectory("wayang-files");
        KnowledgeExtension e = ext(dir);
        ServiceHandler.Connector put = e.connectors().get("file-put");
        Map<String, Object> r = put.call(cfg("acme"), Map.of("fileName", "notes.txt", "text", "hello files"));
        assertEquals(true, r.get("stored"));
        String id = (String) r.get("fileId");
        Map<String, Object> got = e.connectors().get("file-get").call(cfg("acme"), Map.of("fileId", id));
        assertEquals(true, got.get("found"));
        assertEquals("hello files", new String(Base64.getDecoder().decode((String) got.get("contentBase64")), StandardCharsets.UTF_8));
        assertEquals(1, e.connectors().get("file-list").call(cfg("acme"), Map.of()).get("count"));
        assertEquals(true, e.connectors().get("file-delete").call(cfg("acme"), Map.of("fileId", id)).get("deleted"));
        assertEquals(false, e.connectors().get("file-get").call(cfg("acme"), Map.of("fileId", id)).get("found"));
    }

    @Test
    void tenantsNeverSeeEachOthersFiles() throws Exception {
        KnowledgeExtension e = ext(Files.createTempDirectory("wayang-files"));
        String id = (String) e.connectors().get("file-put").call(cfg("acme"), Map.of("fileName", "a.txt", "text", "secret")).get("fileId");
        assertEquals(false, e.connectors().get("file-get").call(cfg("globex"), Map.of("fileId", id)).get("found"));
        assertEquals(false, e.connectors().get("file-delete").call(cfg("globex"), Map.of("fileId", id)).get("deleted"));
        assertEquals(0, e.connectors().get("file-list").call(cfg("globex"), Map.of()).get("count"));
    }

    @Test
    void aFileNameCanNeverEscapeTheDirectory() throws Exception {
        Path dir = Files.createTempDirectory("wayang-files");
        KnowledgeExtension e = ext(dir);
        Map<String, Object> r = e.connectors().get("file-put").call(cfg("t"), Map.of("fileName", "../../etc/passwd", "text", "x"));
        assertEquals("passwd", r.get("fileName"));
        try (Stream<Path> s = Files.walk(dir)) {
            assertTrue(s.allMatch(p -> p.normalize().startsWith(dir)));
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> e.connectors().get("file-put").call(cfg("t"), Map.of("fileName", "..", "text", "x")));
        assertEquals(false, e.connectors().get("file-get").call(cfg("t"), Map.of("fileId", "../../etc/passwd")).get("found"));
    }

    @Test
    void missingMandatoryInputsGiveAnEmptyResultNotAFailure() throws Exception {
        KnowledgeExtension e = ext(Files.createTempDirectory("wayang-files"));
        assertEquals(false, e.connectors().get("file-put").call(cfg("t"), Map.of()).get("stored"));
        assertEquals(false, e.connectors().get("file-put").call(cfg("t"), Map.of("fileName", "a.txt")).get("stored"));
        assertEquals(false, e.connectors().get("file-get").call(cfg("t"), Map.of()).get("found"));
        assertEquals(false, e.connectors().get("file-delete").call(cfg("t"), Map.of()).get("deleted"));
        assertEquals(0, e.connectors().get("rag-ingest").call(Map.of("knowledge", "k"), Map.of()).get("ingested"));
        assertEquals(0, e.connectors().get("rag-search").call(Map.of("knowledge", "k"), Map.of()).get("count"));
    }

    @Test
    void withoutConfiguredStorageTheStepsSayWhy() {
        KnowledgeExtension e = new KnowledgeExtension(null);
        ServiceHandler.ConnectorException x = assertThrows(ServiceHandler.ConnectorException.class,
                () -> e.connectors().get("file-put").call(cfg("t"), Map.of("fileName", "a.txt", "text", "x")));
        assertTrue(x.getMessage().contains("WAYANG_FILES_DIR"));
    }

    @Test
    void ragIngestReadsAStoredFileById() throws Exception {
        KnowledgeExtension e = ext(Files.createTempDirectory("wayang-files"));
        String id = (String) e.connectors().get("file-put").call(cfg("acme"),
                Map.of("fileName", "terms.md", "text", "# Terms\n\nTermination needs 30 days notice.")).get("fileId");
        Map<String, Object> kc = Map.of("knowledge", "stored", "wayang.tenant", "acme");
        Map<String, Object> r = e.connectors().get("rag-ingest").call(kc, Map.of("documents", List.of(Map.of("fileId", id))));
        assertEquals(1, r.get("ingested"));
        Map<?, ?> hit = (Map<?, ?>) ((List<?>) e.connectors().get("rag-search").call(kc, Map.of("query", "termination notice")).get("hits")).get(0);
        assertEquals("terms.md", hit.get("source"));
        // another tenant's id, or an unknown one, is skipped with a reason and never read
        Map<String, Object> other = Map.of("knowledge", "stored2", "wayang.tenant", "globex");
        ServiceHandler.ConnectorException x = assertThrows(ServiceHandler.ConnectorException.class,
                () -> e.connectors().get("rag-ingest").call(other, Map.of("documents", List.of(Map.of("fileId", id)))));
        assertTrue(x.getMessage().contains("no such file"));
    }

    @Test
    void declaredInputsAreExposedInTheCatalogue() {
        KnowledgeExtension e = new KnowledgeExtension(null);
        var ingest = e.components().stream().filter(c -> c.id().equals("wayang.knowledge.rag-ingest")).findFirst().orElseThrow();
        List<?> inputs = (List<?>) ingest.uiHints().get("inputs");
        assertEquals(1, inputs.size());
        assertEquals(true, ((Map<?, ?>) inputs.get(0)).get("mandatory"));
        assertEquals("documents", ((Map<?, ?>) inputs.get(0)).get("name"));
        assertNotNull(ingest.configSchema().get("required"));
        assertFalse(ConnectorInput.isMissing("x") || ConnectorInput.isMissing(List.of(1)));
        assertTrue(ConnectorInput.isMissing("  ") && ConnectorInput.isMissing(List.of()) && ConnectorInput.isMissing(null));
    }
}
