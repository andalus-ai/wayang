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
}
