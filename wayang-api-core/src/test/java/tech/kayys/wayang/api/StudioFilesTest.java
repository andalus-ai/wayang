package tech.kayys.wayang.api;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.Test;

class StudioFilesTest {

    private static Path site() throws Exception {
        Path d = Files.createTempDirectory("studio");
        Files.writeString(d.resolve("index.html"), "<html></html>");
        Files.writeString(d.resolve("main.dart.js"), "x");
        Files.createDirectories(d.resolve("assets"));
        Files.writeString(d.resolve("assets/a.json"), "{}");
        return d;
    }

    @Test
    void servesFilesWithTheirTypeAndFallsBackToTheAppForRoutes() throws Exception {
        StudioFiles s = new StudioFiles(site());
        assertEquals("text/html; charset=utf-8", s.resolve("/").orElseThrow().contentType());
        assertEquals("text/javascript; charset=utf-8", s.resolve("/main.dart.js").orElseThrow().contentType());
        assertEquals("application/json", s.resolve("/assets/a.json").orElseThrow().contentType());
        assertEquals("index.html", s.resolve("/runs/42").orElseThrow().file().getFileName().toString(), "a browser route gets the app");
        assertTrue(s.resolve("/missing.js").isEmpty(), "a missing asset is a 404");
        assertTrue(s.resolve("/assets/missing.png").isEmpty());
    }

    @Test
    void nothingEscapesTheDirectory() throws Exception {
        Path d = site();
        Path outside = Files.createTempDirectory("outside");
        Files.writeString(outside.resolve("secret.txt"), "s");
        Files.createSymbolicLink(d.resolve("link"), outside);
        Files.createSymbolicLink(d.resolve("l.txt"), outside.resolve("secret.txt"));
        StudioFiles s = new StudioFiles(d);
        for (String bad : new String[] {"/../x", "/a/../../x", "/./index.html", "/%2e%2e/x", "\\x", "/a\\b", "/a\0b", "index.html", "/link/secret.txt", "/l.txt"}) {
            assertTrue(s.resolve(bad).isEmpty() || !s.resolve(bad).get().file().startsWith(outside), bad);
            assertFalse(s.resolve(bad).map(x -> x.file().toString().contains("secret")).orElse(false), bad);
        }
        assertTrue(s.resolve("/l.txt").isEmpty());
        assertTrue(s.resolve("/link/secret.txt").isEmpty());
    }

    @Test
    void aDirectoryWithoutIndexIsRefusedAndEntryPointsAreNotCached() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> new StudioFiles(Files.createTempDirectory("empty")));
        assertThrows(IllegalArgumentException.class, () -> new StudioFiles(null));
        assertEquals("no-cache", StudioFiles.cacheControl(Path.of("/x/index.html")));
        assertEquals("public, max-age=3600", StudioFiles.cacheControl(Path.of("/x/a.png")));
        assertTrue(StudioFiles.CSP.contains("frame-ancestors 'none'") && !StudioFiles.CSP.contains("*"));
    }
}
