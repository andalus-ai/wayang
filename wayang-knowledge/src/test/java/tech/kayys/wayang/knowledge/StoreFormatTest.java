package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;

/** The stored layout is versioned: old files still load, files from a newer version are refused instead of misread. */
class StoreFormatTest {

    private static final Map<String, Object> CFG = Map.of("knowledge", "k", "wayang.tenant", "t");

    private static Path definition(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.getFileName().toString().equals("definition.json")).findFirst().orElseThrow();
        }
    }

    private static void ingest(Path dir) throws Exception {
        new KnowledgeExtension(new FileStore(dir, java.time.Clock.systemUTC(), 0, 0)).connectors().get("rag-ingest")
                .call(CFG, Map.of("documents", List.of(Map.of("id", "a", "fileName", "a.txt", "text", "ibis notes"))));
    }

    private static int hits(Path dir) throws Exception {
        return ((List<?>) new KnowledgeExtension(new FileStore(dir, java.time.Clock.systemUTC(), 0, 0)).connectors().get("rag-search")
                .call(CFG, Map.of("query", "ibis")).get("hits")).size();
    }

    @Test
    void newBasesCarryTheFormatVersion() throws Exception {
        Path dir = Files.createTempDirectory("wayang-fmt");
        ingest(dir);
        assertTrue(Files.readString(definition(dir), StandardCharsets.UTF_8).contains("\"format\":1"));
        assertEquals(1, hits(dir));
    }

    @Test
    void filesWithoutAVersionStillLoad() throws Exception {
        Path dir = Files.createTempDirectory("wayang-fmt");
        ingest(dir);
        Path d = definition(dir);
        Files.writeString(d, Files.readString(d, StandardCharsets.UTF_8).replace("\"format\":1,", ""), StandardCharsets.UTF_8);
        assertEquals(1, hits(dir));
    }

    @Test
    void aNewerVersionIsRefusedWithAClearMessage() throws Exception {
        Path dir = Files.createTempDirectory("wayang-fmt");
        ingest(dir);
        Path d = definition(dir);
        Files.writeString(d, Files.readString(d, StandardCharsets.UTF_8).replace("\"format\":1", "\"format\":2"), StandardCharsets.UTF_8);
        Exception e = assertThrows(Exception.class, () -> hits(dir));
        assertTrue(e.getMessage().contains("newer version"), e.getMessage());
    }
}
