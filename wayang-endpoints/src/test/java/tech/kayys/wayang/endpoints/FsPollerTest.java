package tech.kayys.wayang.endpoints;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.attribute.FileTime;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

class FsPollerTest {

    private static final class Tick extends Clock {
        long now = 1_700_000_000_000L;

        @Override public java.time.ZoneId getZone() { return ZoneOffset.UTC; }
        @Override public Clock withZone(java.time.ZoneId z) { return this; }
        @Override public Instant instant() { return Instant.ofEpochMilli(now); }
    }

    private final Tick clock = new Tick();
    private final Path root = tmp();
    private final FsPoller poller = new FsPoller(new FsRoot(root), clock);

    private static Path tmp() {
        try {
            return Files.createTempDirectory("wayang-fspoll");
        } catch (java.io.IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("wayang.tenant", "acme", "path", "in"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private void file(String name, long mtime) throws Exception {
        Path dir = new FsRoot(root).base("acme").resolve("in");
        Files.createDirectories(dir);
        Path f = dir.resolve(name);
        Files.writeString(f, "x");
        Files.setLastModifiedTime(f, FileTime.fromMillis(mtime));
    }

    private List<String> names(Poller.Result r) {
        List<String> n = new ArrayList<>();
        r.events().forEach(e -> n.add((String) e.get("name")));
        return n;
    }

    @Test
    void firstPollSkipsTheExistingBacklogThenSeesNewFilesOnce() throws Exception {
        file("old.csv", clock.now - 60_000);
        Poller.Result first = poller.poll(cfg(), null);
        assertEquals(List.of(), first.events());
        String cursor = first.nextCursor();
        clock.now += 10_000;
        file("a.csv", clock.now - 5_000);
        file("b.csv", clock.now - 4_000);
        Poller.Result r = poller.poll(cfg(), cursor);
        assertEquals(List.of("a.csv", "b.csv"), names(r));
        assertEquals("in/a.csv", r.events().get(0).get("path"));
        assertEquals(List.of(), poller.poll(cfg(), r.nextCursor()).events(), "each file once");
    }

    @Test
    void includeExistingReplaysTheBacklogInOrder() throws Exception {
        file("b.csv", clock.now - 30_000);
        file("a.csv", clock.now - 30_000); // same time: ordered by name
        file("c.csv", clock.now - 40_000);
        assertEquals(List.of("c.csv", "a.csv", "b.csv"), names(poller.poll(cfg("includeExisting", true), null)));
    }

    @Test
    void aFileStillBeingWrittenWaitsUntilItHasSettled() throws Exception {
        String cursor = poller.poll(cfg(), null).nextCursor();
        file("new.csv", clock.now + 500); // modified "just now"
        clock.now += 1000;
        assertEquals(List.of(), poller.poll(cfg(), cursor).events(), "default settle is 2 seconds");
        clock.now += 3000;
        assertEquals(List.of("new.csv"), names(poller.poll(cfg(), cursor)));
        assertEquals(List.of("new.csv"), names(poller.poll(cfg("settleSeconds", 0), cursor)));
    }

    @Test
    void globFiltersAndHiddenTempFilesAreIgnored() throws Exception {
        file("a.csv", clock.now - 30_000);
        file("a.txt", clock.now - 30_000);
        file(".wayang-1.tmp", clock.now - 30_000);
        assertEquals(List.of("a.csv"), names(poller.poll(cfg("includeExisting", true, "glob", "*.csv"), null)));
        assertEquals(List.of("a.csv", "a.txt"), names(poller.poll(cfg("includeExisting", true), null)));
    }

    @Test
    void batchesAreBoundedAndTheCursorContinues() throws Exception {
        for (int i = 0; i < FsPoller.MAX_EVENTS + 5; i++) {
            file(String.format("f%03d.csv", i), clock.now - 100_000 + i);
        }
        Poller.Result r1 = poller.poll(cfg("includeExisting", true), null);
        assertEquals(FsPoller.MAX_EVENTS, r1.events().size());
        assertEquals(5, poller.poll(cfg("includeExisting", true), r1.nextCursor()).events().size());
    }

    @Test
    void aDamagedCursorRestartsInsteadOfStopping_andBadConfigIsAnError() throws Exception {
        file("a.csv", clock.now - 30_000);
        assertEquals(List.of("a.csv"), names(poller.poll(cfg(), "garbage|")));
        assertEquals(List.of("a.csv"), names(poller.poll(cfg(), "notanumber|x")));
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("settleSeconds", 99999), "1|"));
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("path", "../x"), "1|"));
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("glob", "a/b"), "1|"));
        assertEquals(List.of(), poller.poll(cfg("path", "missing"), "1|").events());
    }

    @Test
    void tenantsAreSeparate() throws Exception {
        file("a.csv", clock.now - 30_000);
        Map<String, Object> other = cfg("includeExisting", true);
        other.put("wayang.tenant", "globex");
        assertEquals(List.of(), poller.poll(other, null).events());
        assertTrue(names(poller.poll(cfg("includeExisting", true), null)).contains("a.csv"));
    }
}
