package tech.kayys.wayang.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.util.Base64;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

class FsEndpointsTest {

    private static final Map<String, Object> T = Map.of("wayang.tenant", "acme");

    private static EndpointsExtension ext(Path root) {
        return new EndpointsExtension(new FsRoot(root), Clock.systemUTC());
    }

    private static Map<String, Object> call(EndpointsExtension e, String name, Map<String, Object> config, Map<String, Object> in) throws Exception {
        return e.connectors().get(name).call(config, in);
    }

    @Test
    void offUntilTheOperatorSetsARoot() throws Exception {
        EndpointsExtension e = new EndpointsExtension(new FsRoot(null), Clock.systemUTC());
        ConnectorException x = assertThrows(ConnectorException.class, () -> call(e, "fs-read", T, Map.of("path", "a.txt")));
        assertTrue(x.getMessage().contains("WAYANG_ENDPOINTS_FS_ROOT"), x.getMessage());
    }

    @Test
    void writeThenReadListMoveDelete() throws Exception {
        EndpointsExtension e = ext(Files.createTempDirectory("wayang-fs"));
        assertEquals(true, call(e, "fs-write", T, Map.of("path", "in/a.csv", "content", "id,name\n1,Ünï")).get("written"));
        Map<String, Object> r = call(e, "fs-read", T, Map.of("path", "in/a.csv"));
        assertEquals("id,name\n1,Ünï", r.get("text"));
        assertEquals(true, r.get("found"));
        call(e, "fs-write", T, Map.of("path", "in/b.txt", "content", "b"));
        Map<String, Object> l = call(e, "fs-list", T, Map.of("path", "in", "glob", "*.csv"));
        assertEquals(1, l.get("count"));
        assertEquals("in/a.csv", ((Map<?, ?>) ((List<?>) l.get("files")).get(0)).get("path"));
        assertEquals(true, call(e, "fs-move", T, Map.of("from", "in/a.csv", "to", "archive/2026/a.csv")).get("moved"));
        assertEquals(false, call(e, "fs-read", T, Map.of("path", "in/a.csv")).get("found"));
        assertEquals(true, call(e, "fs-delete", T, Map.of("path", "archive/2026/a.csv")).get("deleted"));
        assertEquals(false, call(e, "fs-delete", T, Map.of("path", "archive/2026/a.csv")).get("deleted"), "already gone is not an error");
        assertEquals(false, call(e, "fs-delete", T, Map.of("path", "archive")).get("deleted"), "never a directory");
    }

    @Test
    void existingFilesAreNotReplacedUnlessAskedAndWritesAreAtomic() throws Exception {
        Path root = Files.createTempDirectory("wayang-fs");
        EndpointsExtension e = ext(root);
        call(e, "fs-write", T, Map.of("path", "x.txt", "content", "one"));
        assertThrows(ConnectorException.class, () -> call(e, "fs-write", T, Map.of("path", "x.txt", "content", "two")));
        assertEquals("one", call(e, "fs-read", T, Map.of("path", "x.txt")).get("text"));
        call(e, "fs-write", Map.of("wayang.tenant", "acme", "overwrite", true), Map.of("path", "x.txt", "content", "two"));
        assertEquals("two", call(e, "fs-read", T, Map.of("path", "x.txt")).get("text"));
        try (var s = Files.walk(root)) {
            assertFalse(s.anyMatch(p -> p.getFileName().toString().endsWith(".tmp")), "no temp file is left behind");
        }
    }

    @Test
    void binaryNeedsBase64AndSizesAreBounded() throws Exception {
        EndpointsExtension e = ext(Files.createTempDirectory("wayang-fs"));
        byte[] bin = {(byte) 0xff, (byte) 0xfe, 0, 1};
        Map<String, Object> b64 = Map.of("wayang.tenant", "acme", "encoding", "base64");
        call(e, "fs-write", b64, Map.of("path", "p.bin", "content", Base64.getEncoder().encodeToString(bin)));
        assertThrows(ConnectorException.class, () -> call(e, "fs-read", T, Map.of("path", "p.bin")), "not UTF-8 text");
        assertEquals(Base64.getEncoder().encodeToString(bin), call(e, "fs-read", b64, Map.of("path", "p.bin")).get("base64"));
        assertThrows(ConnectorException.class, () -> call(e, "fs-write", b64, Map.of("path", "q.bin", "content", "***not base64***")));
        call(e, "fs-write", T, Map.of("path", "big.txt", "content", "x".repeat(2000)));
        ConnectorException x = assertThrows(ConnectorException.class,
                () -> call(e, "fs-read", Map.of("wayang.tenant", "acme", "maxBytes", 1000), Map.of("path", "big.txt")));
        assertTrue(x.getMessage().contains("larger"));
        assertThrows(ConnectorException.class, () -> call(e, "fs-read", Map.of("wayang.tenant", "acme", "maxBytes", 999999999), Map.of("path", "big.txt")));
    }

    @Test
    void nothingEscapesTheTenantDirectory() throws Exception {
        Path root = Files.createTempDirectory("wayang-fs");
        EndpointsExtension e = ext(root);
        call(e, "fs-write", Map.of("wayang.tenant", "globex"), Map.of("path", "secret.txt", "content", "globex data"));
        for (String bad : new String[] {"../u-globex/secret.txt", "/etc/passwd", "a/../../x", "C:/Windows/win.ini", "a\\b", "a\u0000b", "..", "in/../.."}) {
            assertThrows(ConnectorException.class, () -> call(e, "fs-read", T, Map.of("path", bad)), bad);
            assertThrows(ConnectorException.class, () -> call(e, "fs-write", T, Map.of("path", bad, "content", "x")), bad);
            assertThrows(ConnectorException.class, () -> call(e, "fs-list", T, Map.of("path", bad)), bad);
        }
        assertEquals(false, call(e, "fs-read", T, Map.of("path", "secret.txt")).get("found"), "the other tenant's file of the same name is invisible");
        assertEquals(0, call(e, "fs-list", T, Map.of()).get("count"));
        // a tenant id that is not a safe name cannot choose a path either
        call(e, "fs-write", Map.of("wayang.tenant", "../evil"), Map.of("path", "w.txt", "content", "x"));
        try (var s = Files.list(root)) {
            assertTrue(s.noneMatch(p -> p.getFileName().toString().contains("evil") || p.getFileName().toString().equals("..")));
        }
        assertTrue(Files.notExists(root.getParent().resolve("evil")));
    }

    @Test
    void aSymbolicLinkOutOfTheDirectoryIsRefused() throws Exception {
        Path root = Files.createTempDirectory("wayang-fs");
        Path outside = Files.createTempDirectory("wayang-outside");
        Files.writeString(outside.resolve("loot.txt"), "loot");
        EndpointsExtension e = ext(root);
        call(e, "fs-write", T, Map.of("path", "ok.txt", "content", "ok")); // creates the tenant directory
        Path tenantDir = Files.list(root).findFirst().orElseThrow();
        try {
            Files.createSymbolicLink(tenantDir.resolve("link"), outside);
        } catch (UnsupportedOperationException | java.io.IOException ex) {
            return; // this platform cannot make links: nothing to test
        }
        assertThrows(ConnectorException.class, () -> call(e, "fs-read", T, Map.of("path", "link/loot.txt")));
        assertThrows(ConnectorException.class, () -> call(e, "fs-write", T, Map.of("path", "link/new.txt", "content", "x")));
        assertTrue(Files.notExists(outside.resolve("new.txt")));
        assertThrows(ConnectorException.class, () -> call(e, "fs-list", T, Map.of("path", "link")));
    }

    @Test
    void missingInputsGiveAReasonNotAFailure() throws Exception {
        EndpointsExtension e = ext(Files.createTempDirectory("wayang-fs"));
        assertEquals(false, call(e, "fs-read", T, Map.of()).get("found"));
        assertEquals(false, call(e, "fs-write", T, Map.of("path", "a.txt")).get("written"));
        assertEquals(false, call(e, "fs-move", T, Map.of("from", "a")).get("moved"));
        assertEquals(false, call(e, "fs-delete", T, Map.of()).get("deleted"));
        assertEquals(0, call(e, "fs-list", T, Map.of("path", "nope")).get("count"));
    }

    @Test
    void theExtensionDeclaresItsComponentsAndNeedsTheEndpointsFeature() {
        EndpointsExtension e = ext(null);
        assertEquals(java.util.Set.of(tech.kayys.wayang.runtime.Profile.Feature.ENDPOINTS), e.requires());
        assertEquals(6, e.components().size());
        assertEquals(6, e.connectors().size());
        assertTrue(e.pollers().containsKey("fs") && e.pollers().containsKey("jdbc"));
        assertTrue(e.connectors().entrySet().stream().filter(c -> c.getKey().startsWith("fs-")).allMatch(c -> c.getValue().tenantScoped()));
        assertFalse(tech.kayys.wayang.runtime.Profile.AGENTIC.has(tech.kayys.wayang.runtime.Profile.Feature.ENDPOINTS), "agents do not get the file system by default");
        assertTrue(tech.kayys.wayang.runtime.Profile.INTEGRATION.has(tech.kayys.wayang.runtime.Profile.Feature.ENDPOINTS));
        assertEquals("x".getBytes(StandardCharsets.UTF_8).length, 1);
    }
}
