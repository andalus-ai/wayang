package tech.kayys.wayang.runtime;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import org.junit.jupiter.api.Test;

class CronScheduleTest {

    private static Instant at(String s) {
        return Instant.parse(s);
    }

    private static String next(String cron, String zone, String after) {
        Instant n = CronSchedule.parse(cron, zone).next(at(after));
        return n == null ? null : n.toString();
    }

    @Test
    void everyWeekdayMorningInJakarta() {
        // 08:00 in Jakarta (UTC+7, no DST) is 01:00 UTC
        assertEquals("2026-11-02T01:00:00Z", next("0 8 * * MON-FRI", "Asia/Jakarta", "2026-10-31T10:00:00Z")); // Saturday: skips to Monday
        assertEquals("2026-11-03T01:00:00Z", next("0 8 * * 1-5", "Asia/Jakarta", "2026-11-02T01:00:00Z")); // strictly after
        assertEquals("2026-11-02T01:00:00Z", next("0 8 * * 1-5", "Asia/Jakarta", "2026-11-02T00:59:59Z"));
    }

    @Test
    void stepsListsRangesAndTheShortcuts() {
        assertEquals("2026-11-01T10:15:00Z", next("*/15 * * * *", null, "2026-11-01T10:07:00Z"));
        assertEquals("2026-11-01T10:30:00Z", next("0,30 * * * *", "UTC", "2026-11-01T10:07:00Z"));
        assertEquals("2026-11-01T12:00:00Z", next("0 8-18/4 * * *", "UTC", "2026-11-01T10:07:00Z")); // 8, 12, 16
        assertEquals("2026-11-02T00:00:00Z", next("@daily", null, "2026-11-01T10:07:00Z"));
        assertEquals("2026-11-01T11:00:00Z", next("@hourly", null, "2026-11-01T10:07:00Z"));
        assertEquals("2026-11-08T00:00:00Z", next("@weekly", null, "2026-11-01T10:07:00Z")); // Sunday
        assertEquals("2026-12-01T00:00:00Z", next("@monthly", null, "2026-11-01T10:07:00Z"));
        assertEquals("2027-01-01T00:00:00Z", next("@yearly", null, "2026-11-01T10:07:00Z"));
        assertEquals("2026-11-01T10:09:00Z", next("5/2 * * * *", null, "2026-11-01T10:07:00Z")); // 5,7,9... starts at 5
    }

    @Test
    void namesAndBothSundays() {
        assertEquals("2027-01-15T09:00:00Z", next("0 9 15 jan *", null, "2026-11-01T00:00:00Z"));
        assertEquals("2026-11-01T09:00:00Z", next("0 9 * * 0", null, "2026-10-31T00:00:00Z"));
        assertEquals("2026-11-01T09:00:00Z", next("0 9 * * 7", null, "2026-10-31T00:00:00Z"));
        assertEquals("2026-11-01T09:00:00Z", next("0 9 * * SUN", null, "2026-10-31T00:00:00Z"));
        assertEquals("2026-11-03T09:00:00Z", next("0 9 * * ?", null, "2026-11-02T10:00:00Z"));
    }

    @Test
    void dayOfMonthAndWeekdayAreEitherWhenBothAreRestricted() {
        // the 13th OR any Friday: 2026-11-06 is a Friday, 2026-11-13 is also a Friday, 2026-11-13 matches both
        assertEquals("2026-11-06T00:00:00Z", next("0 0 13 * 5", null, "2026-11-01T00:00:00Z"));
        assertEquals("2026-11-13T00:00:00Z", next("0 0 13 * 5", null, "2026-11-12T00:00:00Z"));
        assertEquals("2026-12-13T00:00:00Z", next("0 0 13 * 1", null, "2026-12-07T00:00:00Z")); // 13th (Sunday) before the next Monday the 14th
    }

    @Test
    void anImpossibleDateNeverFires() {
        assertNull(next("0 0 31 2 *", null, "2026-11-01T00:00:00Z"));
        assertEquals("2028-02-29T00:00:00Z", next("0 0 29 2 *", null, "2026-11-01T00:00:00Z"));
    }

    @Test
    void daylightSavingGapsAndRepeats() {
        // New York springs forward on 2027-03-14: 02:30 local does not exist, so the run moves later by the gap (03:30 EDT = 07:30 UTC)
        assertEquals("2027-03-14T07:30:00Z", next("30 2 * * *", "America/New_York", "2027-03-14T00:00:00Z"));
        // falls back on 2026-11-01: 01:30 local happens twice; it fires once, at the first (05:30 UTC), and the next day normally
        assertEquals("2026-11-01T05:30:00Z", next("30 1 * * *", "America/New_York", "2026-11-01T00:00:00Z"));
        assertEquals("2026-11-02T06:30:00Z", next("30 1 * * *", "America/New_York", "2026-11-01T05:30:00Z"));
    }

    @Test
    void anOddLookingScheduleStillAdvancesStrictlyForward() {
        var s = CronSchedule.parse("*/7 3-4 * * *", "Asia/Jakarta");
        Instant t = at("2026-11-01T00:00:00Z");
        Instant prev = t;
        for (int i = 0; i < 200; i++) {
            Instant n = s.next(t);
            assertTrue(n.isAfter(t));
            assertTrue(n.isAfter(prev) || i == 0);
            prev = n;
            t = n;
        }
    }

    @Test
    void badExpressionsAndZonesAreRefusedWithAReason() {
        for (String bad : List.of("", "* * * *", "* * * * * *", "60 * * * *", "* 24 * * *", "* * 0 * *", "* * 32 * *", "* * * 13 *", "* * * * 8",
                "5-1 * * * *", "*/0 * * * *", "a * * * *", "1,,2 * * * *", "1-2-3 * * * *", "* * * FOO *", "x".repeat(101), "@sometimes")) {
            assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse(bad, null), bad);
        }
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse(null, null));
        assertThrows(IllegalArgumentException.class, () -> CronSchedule.parse("* * * * *", "Mars/Olympus"));
        assertEquals("UTC", CronSchedule.parse("* * * * *", "  ").zone().getId());
        assertEquals("0 8 * * 1-5", CronSchedule.parse(" 0 8 * * 1-5 ", "UTC").expression());
    }
}
