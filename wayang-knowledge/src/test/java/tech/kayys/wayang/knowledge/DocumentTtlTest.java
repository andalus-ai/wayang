package tech.kayys.wayang.knowledge;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.stream.Stream;

import org.junit.jupiter.api.Test;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;

/** A document can have a lifetime: after it, nobody can find it, in memory or on disk, with or without a restart. */
class DocumentTtlTest {

    private static final long DAY = 86_400_000L;

    private static final class Tick extends Clock {
        long now = 1_000_000_000_000L;

        @Override
        public java.time.ZoneId getZone() {
            return ZoneOffset.UTC;
        }

        @Override
        public Clock withZone(java.time.ZoneId z) {
            return this;
        }

        @Override
        public Instant instant() {
            return Instant.ofEpochMilli(now);
        }
    }

    private static KnowledgeExtension boot(Path dir, Tick clock) {
        return new KnowledgeExtension(new FileStore(dir, clock, 0, 0));
    }

    private static Map<String, Object> cfg() {
        return Map.of("knowledge", "k", "wayang.tenant", "t");
    }

    private static Map<String, Object> doc(String id, String text, Object ttl) {
        java.util.Map<String, Object> m = new java.util.LinkedHashMap<>(Map.of("id", id, "fileName", id + ".txt", "text", text));
        if (ttl != null) {
            m.put("ttlDays", ttl);
        }
        return m;
    }

    private static Map<String, Object> ingest(KnowledgeExtension e, Map<String, Object> c, Map<String, Object>... docs) throws Exception {
        return e.connectors().get("rag-ingest").call(c, Map.of("documents", List.of(docs)));
    }

    private static List<String> found(KnowledgeExtension e, String q) throws Exception {
        List<?> hits = (List<?>) e.connectors().get("rag-search").call(cfg(), Map.of("query", q)).get("hits");
        return hits.stream().map(h -> String.valueOf(((Map<?, ?>) h).get("source"))).toList();
    }

    private static long files(Path dir) throws Exception {
        try (Stream<Path> s = Files.walk(dir)) {
            return s.filter(p -> p.toString().contains("docs") && p.toString().endsWith(".json")).count();
        }
    }

    @Test
    void aDocumentExpiresAfterItsLifetimeAndTheOthersStay() throws Exception {
        Tick clock = new Tick();
        Path dir = Files.createTempDirectory("wayang-ttl");
        KnowledgeExtension e = boot(dir, clock);
        Map<String, Object> out = ingest(e, cfg(), doc("temp", "zebra contract draft", 2), doc("keep", "zebra handbook forever", null));
        List<?> expiring = (List<?>) out.get("expiring");
        assertEquals(1, expiring.size());
        assertEquals(Instant.ofEpochMilli(clock.now + 2 * DAY).toString(), ((Map<?, ?>) expiring.get(0)).get("expiresAt"));
        assertEquals(2, found(e, "zebra").size());
        clock.now += 2 * DAY - 1;
        assertEquals(2, found(e, "zebra").size(), "not yet");
        clock.now += 1;
        assertEquals(List.of("keep.txt"), found(e, "zebra"), "the moment it expires it is not searchable");
        assertEquals(1, files(dir), "and its stored copy is deleted");
        clock.now += 4000 * DAY;
        assertEquals(List.of("keep.txt"), found(e, "zebra"), "a document without a lifetime never expires");
    }

    @Test
    void theNodeDefaultAppliesAndADocumentsOwnLifetimeWins() throws Exception {
        Tick clock = new Tick();
        KnowledgeExtension e = boot(Files.createTempDirectory("wayang-ttl"), clock);
        Map<String, Object> c = new java.util.LinkedHashMap<>(cfg());
        c.put("ttlDays", 3);
        ingest(e, c, doc("a", "otter notes", null), doc("b", "otter long", 30), doc("c", "otter text lifetime", "10"));
        clock.now += 4 * DAY;
        assertEquals(List.of("b.txt", "c.txt"), found(e, "otter").stream().sorted().toList());
        clock.now += 7 * DAY;
        assertEquals(List.of("b.txt"), found(e, "otter"));
    }

    @Test
    void theCeilingCapsEveryDocumentAndGivesOnesWithoutALifetimeTheCeiling() throws Exception {
        Tick clock = new Tick();
        KnowledgeRegistry reg = new KnowledgeRegistry(new UrlGuard(true, java.util.Set.of()), null, clock);
        RagIngest ingest = new RagIngest(reg, null, 7);
        RagSearch search = new RagSearch(reg);
        Map<String, Object> out = ingest.call(cfg(), Map.of("documents", List.of(doc("none", "heron a", null), doc("long", "heron b", 365), doc("short", "heron c", 2))));
        assertEquals(3, ((List<?>) out.get("expiring")).size(), "all three now have a lifetime");
        clock.now += 3 * DAY;
        assertEquals(2, ((List<?>) search.call(cfg(), Map.of("query", "heron")).get("hits")).size());
        clock.now += 5 * DAY;
        assertEquals(0, ((List<?>) search.call(cfg(), Map.of("query", "heron")).get("hits")).size(), "everything is gone");
    }

    @Test
    void invalidLifetimesSkipThatDocumentOnly() throws Exception {
        Tick clock = new Tick();
        KnowledgeExtension e = boot(Files.createTempDirectory("wayang-ttl"), clock);
        Map<String, Object> out = ingest(e, cfg(), doc("ok", "newt fine", 5), doc("zero", "newt zero", 0), doc("huge", "newt huge", 3651),
                doc("word", "newt word", "soon"), doc("frac", "newt frac", 1.5));
        assertEquals(1, out.get("ingested"));
        List<?> skipped = (List<?>) out.get("skipped");
        assertEquals(4, skipped.size());
        assertTrue(skipped.toString().contains("ttlDays must be a whole number of days from 1 to 3650"), skipped.toString());
        assertEquals(List.of("ok.txt"), found(e, "newt"));
    }

    @Test
    void reAddingADocumentRestartsOrClearsItsLifetime() throws Exception {
        Tick clock = new Tick();
        KnowledgeExtension e = boot(Files.createTempDirectory("wayang-ttl"), clock);
        ingest(e, cfg(), doc("d", "lynx first", 2));
        clock.now += DAY;
        ingest(e, cfg(), doc("d", "lynx second", 2)); // restarts: now expires at +3 days from the start
        clock.now += 2 * DAY - 1;
        assertEquals(List.of("d.txt"), found(e, "lynx"));
        clock.now += 1;
        assertEquals(List.of(), found(e, "lynx"));
        ingest(e, cfg(), doc("d", "lynx third", 1));
        ingest(e, cfg(), doc("d", "lynx fourth", null)); // replaced without a lifetime: it does not inherit the old one
        clock.now += 100 * DAY;
        assertEquals(List.of("d.txt"), found(e, "lynx"));
    }

    @Test
    void aRestartKeepsPendingLifetimesAndDropsWhatExpiredWhileDown() throws Exception {
        Tick clock = new Tick();
        Path dir = Files.createTempDirectory("wayang-ttl");
        ingest(boot(dir, clock), cfg(), doc("soon", "gecko soon", 1), doc("later", "gecko later", 10), doc("forever", "gecko forever", null));
        assertEquals(3, files(dir));
        clock.now += 2 * DAY; // the process was down for two days
        KnowledgeExtension after = boot(dir, clock);
        assertEquals(List.of("forever.txt", "later.txt"), found(after, "gecko").stream().sorted().toList());
        assertEquals(2, files(dir), "the expired copy was deleted from disk, not only hidden");
        clock.now += 9 * DAY;
        assertEquals(List.of("forever.txt"), found(after, "gecko"), "a lifetime that was still pending survived the restart");
        assertEquals(1, files(dir));
    }

    @Test
    void forgetWithNoIdsClearsAnUnusedBaseOnASchedule() throws Exception {
        Tick clock = new Tick();
        Path dir = Files.createTempDirectory("wayang-ttl");
        ingest(boot(dir, clock), cfg(), doc("a", "moth a", 1), doc("b", "moth b", 1), doc("c", "moth c", null));
        clock.now += 5 * DAY;
        KnowledgeExtension fresh = boot(dir, clock); // nobody has searched this base since the restart
        Map<String, Object> out = fresh.connectors().get("rag-forget").call(cfg(), Map.of());
        assertEquals(2, out.get("expired"));
        assertEquals(0, out.get("removed"));
        assertEquals(1, files(dir));
        assertEquals(0, fresh.connectors().get("rag-forget").call(cfg(), Map.of()).get("expired"));
        // a base that does not exist is not an error for a scheduled purge
        assertEquals(0, fresh.connectors().get("rag-forget").call(Map.of("knowledge", "never-made", "wayang.tenant", "t"), Map.of()).get("expired"));
        // with ids, expired documents are reported too
        ingest(fresh, cfg(), doc("d", "moth d", 1));
        clock.now += 2 * DAY;
        Map<String, Object> o2 = fresh.connectors().get("rag-forget").call(cfg(), Map.of("documentIds", List.of("c")));
        assertEquals(1, o2.get("removed"));
        assertEquals(1, o2.get("expired"));
    }

    @Test
    void lifetimesAreKeptPerTenant() throws Exception {
        Tick clock = new Tick();
        KnowledgeExtension e = boot(Files.createTempDirectory("wayang-ttl"), clock);
        Map<String, Object> other = Map.of("knowledge", "k", "wayang.tenant", "u");
        ingest(e, cfg(), doc("x", "crane mine", 1));
        ingest(e, other, doc("x", "crane theirs", null));
        clock.now += 2 * DAY;
        assertEquals(List.of(), found(e, "crane"));
        List<?> theirs = (List<?>) e.connectors().get("rag-search").call(other, Map.of("query", "crane")).get("hits");
        assertEquals(1, theirs.size(), "the other tenant's document of the same id is untouched");
        assertFalse(theirs.toString().contains("mine"));
    }
}
