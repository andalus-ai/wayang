package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ServiceHandler;

class NotesTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-01-01T00:00:00Z"), ZoneOffset.UTC);

    private static Map<String, Object> cfg(String op, Object... kv) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("operation", op);
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void addListSearchDeleteSurviveARestartWhenThereIsADirectory() throws Exception {
        Path dir = Files.createTempDirectory("notes");
        Notes n = new Notes(dir, CLOCK);
        assertEquals("n1", n.call(cfg("add"), Map.of("text", "Buy milk", "title", "Shopping")).get("id"));
        assertEquals("n2", n.call(cfg("add"), Map.of("text", "Call Ann about the Plan")).get("id"));
        Notes again = new Notes(dir, CLOCK); // a restart
        @SuppressWarnings("unchecked")
        var listed = (List<Map<String, Object>>) again.call(cfg("list"), Map.of()).get("notes");
        assertEquals(List.of("n2", "n1"), listed.stream().map(m -> m.get("id")).toList(), "newest first");
        assertEquals("2026-01-01T00:00:00Z", listed.get(0).get("created"));
        @SuppressWarnings("unchecked")
        var hit = (List<Map<String, Object>>) again.call(cfg("search"), Map.of("q", "SHOPPING")).get("notes");
        assertEquals(1, hit.size(), "case-insensitive, title is searched too");
        assertEquals(Map.of("deleted", true, "count", 1), again.call(cfg("delete"), Map.of("id", "n1")));
        assertEquals(Map.of("deleted", false, "count", 1), again.call(cfg("delete"), Map.of("id", "n1")));
        assertEquals("n3", again.call(cfg("add"), Map.of("text", "x")).get("id"), "ids never repeat a live one");
    }

    @Test
    void namespacesAreSeparateAndMemoryOnlyWorks() throws Exception {
        Notes n = new Notes(null, CLOCK);
        n.call(cfg("add", "namespace", "work"), Map.of("text", "w"));
        assertEquals(0, n.call(cfg("list", "namespace", "home"), Map.of()).get("count"));
        assertEquals(1, n.call(cfg("list", "namespace", "work"), Map.of()).get("count"));
    }

    @Test
    void inputsAreBoundedAndPathsCannotEscape() throws Exception {
        Path dir = Files.createTempDirectory("notes");
        Notes n = new Notes(dir, CLOCK);
        for (Map<String, Object> bad : List.<Map<String, Object>>of(Map.of(), Map.of("text", " "), Map.of("text", "x".repeat(4001)),
                Map.of("text", "ok", "title", "t".repeat(201)))) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> n.call(cfg("add"), bad), bad.keySet().toString());
        }
        for (String ns : new String[] {"../x", "a/b", "a b", "x".repeat(41)}) {
            assertThrows(ServiceHandler.ConnectorException.class, () -> n.call(cfg("list", "namespace", ns), Map.of()), ns);
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> n.call(cfg("delete"), Map.of("id", "../n1")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> n.call(cfg("search"), Map.of("q", "")));
        assertThrows(ServiceHandler.ConnectorException.class, () -> n.call(cfg("drop"), Map.of()));
        assertFalse(Files.exists(dir.resolve("..").resolve("x.json")));
    }

    @Test
    void aCorruptFileIsAnErrorNotAnEmptyNotebook() throws Exception {
        Path dir = Files.createTempDirectory("notes");
        Files.writeString(dir.resolve("default.json"), "{not json");
        Notes n = new Notes(dir, CLOCK);
        assertThrows(ServiceHandler.ConnectorException.class, () -> n.call(cfg("add"), Map.of("text", "x")), "adding must not overwrite what it cannot read");
    }

    @Test
    void theNotebookHasALimit() throws Exception {
        Notes n = new Notes(null, CLOCK);
        for (int i = 0; i < Notes.MAX_NOTES; i++) {
            n.call(cfg("add"), Map.of("text", "n" + i));
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> n.call(cfg("add"), Map.of("text", "one more")));
        assertEquals(Notes.MAX_NOTES, n.call(cfg("list"), Map.of("limit", 1)).get("count"));
    }
}
