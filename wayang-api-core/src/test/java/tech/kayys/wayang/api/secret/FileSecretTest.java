package tech.kayys.wayang.api.secret;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;

import static org.junit.jupiter.api.Assertions.*;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.SecretResolver;

class FileSecretTest {

    private final List<String> log = new ArrayList<>();

    private Path dir() throws Exception {
        Path d = Files.createTempDirectory("wsec");
        Files.createDirectories(d.resolve("acme"));
        Files.createDirectories(d.resolve("other"));
        return d;
    }

    private FileSecretResolver r(Path d) {
        return new FileSecretResolver(d, log::add);
    }

    @Test
    void readsTheTenantsFileAndDropsOneTrailingNewline() throws Exception {
        Path d = dir();
        Files.writeString(d.resolve("acme/API_KEY"), "s3cret\n");
        Files.writeString(d.resolve("acme/two"), "x\n\n");
        Files.writeString(d.resolve("acme/crlf"), "y\r\n");
        assertEquals("s3cret", r(d).resolve("acme", "file.API_KEY"));
        assertEquals("x\n", r(d).resolve("acme", "file.two"));
        assertEquals("y", r(d).resolve("acme", "file.crlf"));
    }

    @Test
    void tenantsAreIsolatedAndMissingIsNull() throws Exception {
        Path d = dir();
        Files.writeString(d.resolve("other/K"), "theirs");
        assertNull(r(d).resolve("acme", "file.K"));
        assertEquals("theirs", r(d).resolve("other", "file.K"));
        assertNull(r(d).resolve(null, "file.K"));
        assertNull(r(d).resolve("acme", "vault.K"));
        assertNull(r(d).resolve("acme", "K"));
    }

    @Test
    void traversalAndOddNamesAreRefused() throws Exception {
        Path d = dir();
        Files.writeString(d.resolve("outside"), "nope");
        Files.writeString(d.resolve("acme/ok"), "fine");
        for (String n : new String[] { "file.", "file..", "file...x", "file.a..b", "file./etc/passwd", "file.a/b", "file.\\x", "file.a b", "file.-x" }) {
            assertNull(r(d).resolve("acme", n), n);
        }
        for (String t : new String[] { "..", "../acme", "a/b", "", ".hidden/..", "a..b" }) {
            assertNull(r(d).resolve(t, "file.outside"), t);
        }
        assertEquals("fine", r(d).resolve("acme", "file.ok"));
    }

    @Test
    void aSymlinkOutOfTheDirectoryIsRefusedButOneInsideIsFine() throws Exception {
        Path d = dir();
        Path out = Files.createTempFile("outside", ".txt");
        Files.writeString(out, "leak");
        Files.createSymbolicLink(d.resolve("acme/esc"), out);
        Files.writeString(d.resolve("acme/real"), "inside");
        Files.createSymbolicLink(d.resolve("acme/alias"), Path.of("real"));
        assertNull(r(d).resolve("acme", "file.esc"));
        assertEquals("inside", r(d).resolve("acme", "file.alias"));
        assertTrue(log.stream().anyMatch(m -> m.contains("refused")));
        assertTrue(log.stream().noneMatch(m -> m.contains("leak") || m.contains(out.toString())), "log must not carry content or paths");
    }

    @Test
    void directoriesEmptyBinaryAndOversizedFilesReadAsMissing() throws Exception {
        Path d = dir();
        Files.createDirectories(d.resolve("acme/sub"));
        Files.writeString(d.resolve("acme/empty"), "\n");
        Files.write(d.resolve("acme/bin"), new byte[] { (byte) 0xff, (byte) 0xfe, 1 });
        Files.write(d.resolve("acme/big"), "a".repeat(SecretService.MAX_VALUE_CHARS * 4 + 1).getBytes(StandardCharsets.UTF_8));
        Files.writeString(d.resolve("acme/long"), "a".repeat(SecretService.MAX_VALUE_CHARS + 1));
        for (String n : new String[] { "sub", "empty", "bin", "big", "long" }) {
            assertNull(r(d).resolve("acme", "file." + n), n);
        }
    }

    @Test
    void aRotatedFileIsSeenOnTheNextReadBecauseNothingIsCached() throws Exception {
        Path d = dir();
        Files.writeString(d.resolve("acme/K"), "one");
        assertEquals("one", r(d).resolve("acme", "file.K"));
        Files.writeString(d.resolve("acme/K"), "two");
        assertEquals("two", r(d).resolve("acme", "file.K"));
    }

    @Test
    void routingSendsEachPrefixToItsSourceAndMissingSourcesFailClosed() throws Exception {
        Path d = dir();
        Files.writeString(d.resolve("acme/K"), "from-file");
        SecretResolver local = (t, n) -> "local:" + n;
        SecretResolver vault = (t, n) -> "vault:" + n;
        SecretResolver both = FileSecretResolver.routing(local, vault, r(d));
        assertEquals("from-file", both.resolve("acme", "file.K"));
        assertEquals("vault:vault.X", both.resolve("acme", "vault.X"));
        assertEquals("local:PLAIN", both.resolve("acme", "PLAIN"));
        SecretResolver none = FileSecretResolver.routing(local, null, null);
        assertNull(none.resolve("acme", "file.K"));
        assertNull(none.resolve("acme", "vault.X"));
        assertEquals("local:PLAIN", none.resolve("acme", "PLAIN"));
    }

    @Test
    void fileAndVaultNamesCannotBeStoredLocally() {
        var svc = new SecretService(new InMemorySecretStore(), new SecretCipher(java.util.Map.of("k", new byte[32]), "k"),
                java.time.Clock.systemUTC());
        var admin = new Principal("acme", "ann", Set.of(Role.ADMIN));
        assertThrows(ApiException.class, () -> svc.put(admin, "file.X", "v"));
        assertThrows(ApiException.class, () -> svc.put(admin, "vault.X", "v"));
        assertDoesNotThrow(() -> svc.put(admin, "files.X", "v"));
    }
}
