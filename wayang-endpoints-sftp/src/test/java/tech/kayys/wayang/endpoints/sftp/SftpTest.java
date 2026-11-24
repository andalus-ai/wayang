package tech.kayys.wayang.endpoints.sftp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/** The SFTP endpoints against a fake session (no server): what is asked of the server, and what is refused before it is. */
class SftpTest {

    static final String FP = "SHA256:" + "A".repeat(43);

    static final class Fake implements Sftp.Opener {
        final Map<String, byte[]> files = new LinkedHashMap<>();
        final List<Sftp.Entry> entries = new ArrayList<>();
        final List<String> log = new ArrayList<>();
        final List<Sftp.Target> targets = new ArrayList<>();
        Exception openFailure;

        @Override
        public Sftp.Session open(Sftp.Target t) throws Exception {
            if (openFailure != null) {
                throw openFailure;
            }
            targets.add(t);
            return new Sftp.Session() {
                @Override
                public List<Sftp.Entry> list(String dir) {
                    log.add("list " + dir);
                    return entries;
                }

                @Override
                public byte[] read(String path, int max) {
                    log.add("read " + path);
                    byte[] b = files.get(path);
                    if (b == null) {
                        throw new IllegalStateException("No such file");
                    }
                    return b.length > max ? null : b;
                }

                @Override
                public void write(String path, byte[] data, boolean overwrite) throws Exception {
                    log.add("write " + path + (overwrite ? " overwrite" : ""));
                    if (!overwrite && files.containsKey(path)) {
                        throw new java.nio.file.FileAlreadyExistsException(path);
                    }
                    files.put(path, data);
                }

                @Override
                public void delete(String path) {
                    log.add("delete " + path);
                    files.remove(path);
                }

                @Override
                public void close() {
                    log.add("close");
                }
            };
        }
    }

    private static Sftp sftp(Fake f) {
        return new Sftp(Set.of("files.example.com:22", "alt.example.com:2222"), f);
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("host", "files.example.com", "user", "svc", "password", "pw-s3cr3t", "hostKey", FP));
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void writesThenReadsBackAndListsAndDeletes() throws Exception {
        Fake f = new Fake();
        var write = new SftpConnectors.Write(sftp(f));
        assertEquals(6L, write.call(cfg(), Map.of("path", "out/a.txt", "content", "héllo")).get("size"));
        assertEquals("héllo", new String(f.files.get("out/a.txt"), StandardCharsets.UTF_8));
        var read = new SftpConnectors.Read(sftp(f)).call(cfg(), Map.of("path", "out/a.txt"));
        assertEquals("héllo", read.get("content"));
        var b64 = new SftpConnectors.Read(sftp(f)).call(cfg(), Map.of("path", "out/a.txt", "encoding", "base64"));
        assertEquals("aMOpbGxv", b64.get("content"));
        f.entries.add(new Sftp.Entry("b.csv", true, 3, 100));
        f.entries.add(new Sftp.Entry("a.csv", true, 3, 100));
        f.entries.add(new Sftp.Entry("sub", false, 0, 100));
        var list = new SftpConnectors.ListFiles(sftp(f)).call(cfg(), Map.of("path", "in"));
        assertEquals(2, list.get("count"));
        assertEquals("in/a.csv", ((Map<?, ?>) ((List<?>) list.get("files")).get(0)).get("path"));
        new SftpConnectors.Delete(sftp(f)).call(cfg(), Map.of("path", "out/a.txt"));
        assertFalse(f.files.containsKey("out/a.txt"));
        assertEquals("close", f.log.get(f.log.size() - 1), "every session is closed");
    }

    @Test
    void anExistingFileIsNotReplacedUnlessAskedAndALargeOrBinaryFileIsRefused() throws Exception {
        Fake f = new Fake();
        f.files.put("x.bin", new byte[] {(byte) 0xff, (byte) 0xfe});
        f.files.put("big.txt", new byte[100]);
        var w = new SftpConnectors.Write(sftp(f));
        var e = assertThrows(ConnectorException.class, () -> w.call(cfg(), Map.of("path", "x.bin", "content", "new")));
        assertTrue(e.getMessage().contains("overwrite"));
        w.call(cfg(), Map.of("path", "x.bin", "content", "new", "overwrite", true));
        f.files.put("bin", new byte[] {(byte) 0xff, (byte) 0xfe});
        var r = new SftpConnectors.Read(sftp(f));
        assertTrue(assertThrows(ConnectorException.class, () -> r.call(cfg(), Map.of("path", "bin"))).getMessage().contains("base64"));
        assertTrue(assertThrows(ConnectorException.class, () -> r.call(cfg(), Map.of("path", "big.txt", "maxBytes", 10))).getMessage().contains("larger"));
        assertThrows(ConnectorException.class, () -> r.call(cfg(), Map.of("path", "big.txt", "maxBytes", Sftp.HARD_MAX_BYTES + 1)));
        assertThrows(ConnectorException.class, () -> w.call(cfg(), Map.of("path", "y", "content", "!!", "encoding", "base64")));
    }

    @Test
    void nothingReachesTheServerUntilPermittedAndPathsCannotEscape() {
        Fake f = new Fake();
        var off = new Sftp(Set.of(), f);
        assertThrows(ConnectorException.class, () -> new SftpConnectors.Read(off).call(cfg(), Map.of("path", "a")));
        var r = new SftpConnectors.Read(sftp(f));
        for (Map<String, Object> bad : List.of(cfg("host", "evil.example.com"), cfg("port", 2222), cfg("port", "x"), cfg("port", 70000), cfg("host", "a b"),
                cfg("hostKey", "nope"), cfg("hostKey", "MD5:aa:bb"), cfg("user", " "))) {
            assertThrows(ConnectorException.class, () -> r.call(bad, Map.of("path", "a")), String.valueOf(bad.keySet()));
        }
        for (String badPath : new String[] {"/etc/passwd", "../x", "a/../../x", "a//b", "a\\b", "a\0b", "C:/x", "", "a/./b"}) {
            assertThrows(ConnectorException.class, () -> r.call(cfg(), Map.of("path", badPath)), badPath);
        }
        assertTrue(f.targets.isEmpty(), "no connection was opened for any of these");
    }

    @Test
    void theHostKeyAndExactlyOneCredentialAreMandatoryAndSecretsAreNotEchoed() throws Exception {
        Fake f = new Fake();
        var r = new SftpConnectors.Read(sftp(f));
        Map<String, Object> noKey = cfg();
        noKey.remove("hostKey");
        assertTrue(assertThrows(ConnectorException.class, () -> r.call(noKey, Map.of("path", "a"))).getMessage().contains("hostKey"));
        Map<String, Object> none = cfg();
        none.remove("password");
        assertThrows(ConnectorException.class, () -> r.call(none, Map.of("path", "a")));
        assertThrows(ConnectorException.class, () -> r.call(cfg("privateKey", "-----BEGIN-----x"), Map.of("path", "a")), "both given");
        f.files.put("a", new byte[] {1});
        r.call(cfg(), Map.of("path", "a"));
        assertEquals("pw-s3cr3t", f.targets.get(0).password());
        assertFalse(f.targets.get(0).toString().contains("pw-s3cr3t"));
        f.openFailure = new java.io.IOException("auth failed for svc with pw-s3cr3t at banner");
        var e = assertThrows(ConnectorException.class, () -> r.call(cfg(), Map.of("path", "a")));
        assertFalse(e.getMessage().contains("pw-s3cr3t") || e.getMessage().contains("banner"), e.getMessage());
        assertFalse(e.retryable());
        f.openFailure = new java.net.SocketTimeoutException("slow");
        assertTrue(assertThrows(ConnectorException.class, () -> r.call(cfg(), Map.of("path", "a"))).retryable());
    }

    private static Clock at(long epoch) {
        return Clock.fixed(Instant.ofEpochSecond(epoch), ZoneOffset.UTC);
    }

    @Test
    void theTriggerReportsNewFilesOnceInOrderAndOnlyWhenSettled() throws Exception {
        Fake f = new Fake();
        f.entries.add(new Sftp.Entry("old.csv", true, 1, 100));
        var p = new SftpPoller(sftp(f), at(1000));
        var first = p.poll(cfg("path", "in", "glob", "*.csv"), null);
        assertTrue(first.events().isEmpty(), "existing files are not replayed by default");
        assertEquals("100|old.csv", first.nextCursor());

        f.entries.add(new Sftp.Entry("b.csv", true, 2, 200));
        f.entries.add(new Sftp.Entry("a.csv", true, 2, 200));
        f.entries.add(new Sftp.Entry("c.txt", true, 2, 210));
        f.entries.add(new Sftp.Entry("fresh.csv", true, 2, 998));
        f.entries.add(new Sftp.Entry("dir.csv", false, 0, 300));
        var second = p.poll(cfg("path", "in", "glob", "*.csv"), first.nextCursor());
        assertEquals(List.of("in/a.csv", "in/b.csv"), second.events().stream().map(e -> e.get("path")).toList(), "ordered, matching, settled, files only");
        assertEquals("200|b.csv", second.nextCursor());

        var third = p.poll(cfg("path", "in", "glob", "*.csv"), second.nextCursor());
        assertTrue(third.events().isEmpty());
        assertNull(third.nextCursor());

        var later = new SftpPoller(sftp(f), at(2000)).poll(cfg("path", "in", "glob", "*.csv"), second.nextCursor());
        assertEquals(List.of("in/fresh.csv"), later.events().stream().map(e -> e.get("path")).toList());
    }

    @Test
    void includeExistingMaxFilesAndBadSettingsAreHonoured() throws Exception {
        Fake f = new Fake();
        for (int i = 0; i < 5; i++) {
            f.entries.add(new Sftp.Entry("f" + i, true, 1, 100 + i));
        }
        var p = new SftpPoller(sftp(f), at(10_000));
        var r = p.poll(cfg("includeExisting", true, "maxFiles", 2), null);
        assertEquals(2, r.events().size());
        assertEquals("101|f1", r.nextCursor());
        var rest = p.poll(cfg("includeExisting", true, "maxFiles", 10), r.nextCursor());
        assertEquals(3, rest.events().size());
        for (Map<String, Object> bad : List.of(cfg("maxFiles", 0), cfg("maxFiles", 51), cfg("settleSeconds", -1), cfg("glob", "a/b"), cfg("path", "../x"))) {
            assertThrows(ConnectorException.class, () -> p.poll(bad, null), String.valueOf(bad));
        }
        for (String bad : new String[] {"x", "|a", "abc|a"}) {
            assertThrows(ConnectorException.class, () -> p.poll(cfg(), bad), bad);
        }
        Poller.Result none = new SftpPoller(sftp(new Fake()), at(10_000)).poll(cfg(), null);
        assertEquals("9995|", none.nextCursor(), "an empty directory still pins the starting time");
    }

    @Test
    void theExtensionExposesTheEndpointsAndDescribesThem() {
        var e = new SftpExtension(sftp(new Fake()), at(0));
        assertEquals(Set.of("sftp-list", "sftp-read", "sftp-write", "sftp-delete"), e.connectors().keySet());
        assertEquals(Set.of("sftp"), e.pollers().keySet());
        assertEquals(4, e.components().size());
        assertEquals(Set.of("host", "user", "hostKey"), Set.copyOf((List<?>) e.components().get(0).configSchema().get("required")));
    }
}
