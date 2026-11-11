package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.junit.jupiter.api.Test;

class RateLimiterTest {

    private static final Instant T = Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void atMostNPerWindowPerKeyAndTheWindowSlides() {
        var l = new RateLimiter(2, Duration.ofHours(1));
        assertTrue(l.allow("a", T));
        assertTrue(l.allow("a", T.plusSeconds(10)));
        assertFalse(l.allow("a", T.plusSeconds(20)));
        assertFalse(l.allow("a", T.plusSeconds(3599)), "a refused event is not counted, and the first has not left the window");
        assertTrue(l.allow("b", T.plusSeconds(20)), "another key has its own count");
        assertTrue(l.allow("a", T.plusSeconds(3600)), "the first event left the window exactly one hour later");
        assertFalse(l.allow("a", T.plusSeconds(3601)));
        assertTrue(l.allow("a", T.plusSeconds(3611)));
    }

    @Test
    void keysAreBounded() {
        var l = new RateLimiter(1, Duration.ofHours(1));
        for (int i = 0; i < RateLimiter.MAX_KEYS + 5; i++) {
            assertTrue(l.allow("k" + i, T));
        }
        assertTrue(l.allow("k0", T), "the least recently used key was forgotten");
        assertFalse(l.allow("k" + (RateLimiter.MAX_KEYS + 4), T), "a recent one is still counted");
    }

    @Test
    void theTriggerSettingIsStrict() {
        assertNull(RateLimiter.fromTriggerConfig(Map.of()));
        assertNotNull(RateLimiter.fromTriggerConfig(Map.of("rateLimit", Map.of("perHour", 30))));
        for (Object bad : new Object[] {"30", Map.of(), Map.of("perHour", 0), Map.of("perHour", 10001), Map.of("perHour", 1.5), Map.of("perHour", "5"),
                Map.of("perDay", 0), Map.of("perDay", 10001), Map.of("perWeek", 5), Map.of("perHour", 5, "perDay", 1, "perWeek", 1)}) {
            assertThrows(IllegalArgumentException.class, () -> RateLimiter.fromTriggerConfig(Map.of("rateLimit", bad)), String.valueOf(bad));
        }
        assertThrows(IllegalArgumentException.class, () -> new RateLimiter(0, Duration.ofHours(1)));
        assertNotNull(RateLimiter.fromTriggerConfig(Map.of("rateLimit", Map.of("perHour", 5, "perDay", 20))));
        assertNotNull(RateLimiter.fromTriggerConfig(Map.of("rateLimit", Map.of("perDay", 20))));
    }

    @Test
    void aDayLimitHoldsEvenWhenTheHourlyRateIsRespected() {
        var l = RateLimiter.fromTriggerConfig(Map.of("rateLimit", Map.of("perHour", 2, "perDay", 3)));
        assertTrue(l.allow("a", T));
        assertTrue(l.allow("a", T.plusSeconds(1)));
        assertFalse(l.allow("a", T.plusSeconds(2)), "hour limit");
        assertTrue(l.allow("a", T.plusSeconds(3700)), "a new hour: the third of the day");
        assertFalse(l.allow("a", T.plusSeconds(7400)), "the hour is free but the day is used up");
        assertFalse(l.allow("a", T.plusSeconds(86_399)), "refused events were not counted, and the first is still inside the day");
        assertTrue(l.allow("a", T.plusSeconds(86_401)), "the first event left the day");
        assertTrue(l.allow("b", T), "another chat has its own day");
    }

    @Test
    void aJournalKeepsTheCountAcrossARestart() {
        var journal = new RateLimiter.MemoryJournal();
        var before = RateLimiter.fromTriggerConfig(Map.of("rateLimit", Map.of("perDay", 2))).withJournal(journal, "t1");
        assertTrue(before.allow("chat", T));
        assertTrue(before.allow("chat", T.plusSeconds(10)));
        assertFalse(before.allow("chat", T.plusSeconds(20)));
        assertEquals(2, journal.size(), "only allowed events are written down");
        // a restart: a brand new limiter over the same journal
        var after = RateLimiter.fromTriggerConfig(Map.of("rateLimit", Map.of("perDay", 2))).withJournal(journal, "t1");
        assertFalse(after.allow("chat", T.plusSeconds(60)), "the day's allowance is not handed out again");
        assertTrue(after.allow("other", T.plusSeconds(60)), "another chat is unaffected");
        assertTrue(after.allow("chat", T.plusSeconds(86_405)), "the first event left the day");
    }

    @Test
    void scopesDoNotShareCounts() {
        var journal = new RateLimiter.MemoryJournal();
        var a = new RateLimiter(1, Duration.ofHours(1)).withJournal(journal, "trigger-a");
        assertTrue(a.allow("chat", T));
        var b = new RateLimiter(1, Duration.ofHours(1)).withJournal(journal, "trigger-b");
        assertTrue(b.allow("chat", T.plusSeconds(1)));
    }

    @Test
    void aFailingJournalNeverBlocksAndTheLimitStillHoldsInMemory() {
        RateLimiter.Journal broken = new RateLimiter.Journal() {
            public java.util.List<Instant> load(String scope, String key, Instant since) { throw new IllegalStateException("down"); }
            public void record(String scope, String key, Instant at) { throw new IllegalStateException("down"); }
            public void purgeBefore(Instant before) { throw new IllegalStateException("down"); }
        };
        var l = new RateLimiter(2, Duration.ofHours(1)).withJournal(broken, "s");
        assertTrue(l.allow("a", T));
        assertTrue(l.allow("a", T.plusSeconds(1)));
        assertFalse(l.allow("a", T.plusSeconds(2)));
    }

    @Test
    void purgeForgetsOldEventsOnly() {
        var j = new RateLimiter.MemoryJournal();
        j.record("s", "k", T);
        j.record("s", "k", T.plusSeconds(1000));
        j.purgeBefore(T.plusSeconds(500));
        assertEquals(1, j.size());
    }
}
