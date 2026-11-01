package tech.kayys.wayang.runtime;

import java.time.DateTimeException;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.temporal.ChronoUnit;
import java.util.BitSet;
import java.util.Locale;
import java.util.Map;

/**
 * A standard five-field cron expression ({@code minute hour day-of-month month day-of-week}) in a time zone.
 *
 * <p>Fields take {@code *}, numbers, ranges ({@code 1-5}), lists ({@code 1,15}), steps ({@code *}{@code /10}, {@code 8-18/2}) and, for month and
 * weekday, English names ({@code JAN}, {@code MON-FRI}). Weekday 0 and 7 are both Sunday. {@code ?} means {@code *}. The shortcuts
 * {@code @hourly @daily @midnight @weekly @monthly @yearly} are accepted. When both day-of-month and weekday are restricted a day matches if
 * <em>either</em> does, as in Vixie cron. There are no seconds: a schedule fires at most once a minute.
 *
 * <p>Local times that do not exist (spring forward) are moved later by the length of the gap (02:30 becomes 03:30); times that happen twice (fall back) fire once,
 * at the first of them.
 */
public final class CronSchedule {

    private static final Map<String, String> SHORTCUTS = Map.of("@hourly", "0 * * * *", "@daily", "0 0 * * *", "@midnight", "0 0 * * *",
            "@weekly", "0 0 * * 0", "@monthly", "0 0 1 * *", "@yearly", "0 0 1 1 *", "@annually", "0 0 1 1 *");
    private static final String[] MONTHS = {"JAN", "FEB", "MAR", "APR", "MAY", "JUN", "JUL", "AUG", "SEP", "OCT", "NOV", "DEC"};
    private static final String[] DAYS = {"SUN", "MON", "TUE", "WED", "THU", "FRI", "SAT"};
    private static final int SEARCH_YEARS = 8;

    private final BitSet minutes;
    private final BitSet hours;
    private final BitSet days;
    private final BitSet months;
    private final BitSet weekdays;
    private final boolean dayRestricted;
    private final boolean weekdayRestricted;
    private final ZoneId zone;
    private final String expression;

    private CronSchedule(String expression, ZoneId zone, BitSet minutes, BitSet hours, BitSet days, BitSet months, BitSet weekdays,
            boolean dayRestricted, boolean weekdayRestricted) {
        this.expression = expression;
        this.zone = zone;
        this.minutes = minutes;
        this.hours = hours;
        this.days = days;
        this.months = months;
        this.weekdays = weekdays;
        this.dayRestricted = dayRestricted;
        this.weekdayRestricted = weekdayRestricted;
    }

    /**
     * @param zone an IANA zone id such as {@code Asia/Jakarta}; null or blank means UTC
     * @throws IllegalArgumentException when the expression or the zone is not valid
     */
    public static CronSchedule parse(String expression, String zone) {
        if (expression == null || expression.isBlank() || expression.length() > 100) {
            throw new IllegalArgumentException("cron must be a five-field expression of at most 100 characters");
        }
        String e = expression.strip();
        e = SHORTCUTS.getOrDefault(e.toLowerCase(Locale.ROOT), e);
        String[] f = e.split("\\s+");
        if (f.length != 5) {
            throw new IllegalArgumentException("cron needs five fields: minute hour day-of-month month day-of-week");
        }
        ZoneId z;
        try {
            z = zone == null || zone.isBlank() ? ZoneId.of("UTC") : ZoneId.of(zone.strip());
        } catch (DateTimeException ex) {
            throw new IllegalArgumentException("timezone is not a known time zone id (for example Asia/Jakarta or UTC)");
        }
        BitSet wd = field(f[4], 0, 7, DAYS, "day-of-week");
        if (wd.get(7)) {
            wd.set(0);
        }
        wd.clear(7);
        return new CronSchedule(expression.strip(), z, field(f[0], 0, 59, null, "minute"), field(f[1], 0, 23, null, "hour"),
                field(f[2], 1, 31, null, "day-of-month"), field(f[3], 1, 12, MONTHS, "month"), wd,
                !isAll(f[2]), !isAll(f[4]));
    }

    private static boolean isAll(String f) {
        return f.equals("*") || f.equals("?") || f.startsWith("*/");
    }

    private static BitSet field(String text, int min, int max, String[] names, String what) {
        BitSet out = new BitSet(max + 1);
        for (String part : text.split(",", -1)) {
            if (part.isEmpty()) {
                throw new IllegalArgumentException("cron " + what + " has an empty item");
            }
            int step = 1;
            String range = part;
            int slash = part.indexOf('/');
            if (slash >= 0) {
                range = part.substring(0, slash);
                step = number(part.substring(slash + 1), 0, 0, null, what + " step");
                if (step < 1) {
                    throw new IllegalArgumentException("cron " + what + " step must be at least 1");
                }
            }
            int lo;
            int hi;
            if (range.equals("*") || range.equals("?")) {
                lo = min;
                hi = max == 7 ? 6 : max; // '*' for weekdays is 0-6, never 7 as well
            } else if (range.indexOf('-') > 0) {
                String[] r = range.split("-", -1);
                if (r.length != 2) {
                    throw new IllegalArgumentException("cron " + what + " range '" + range + "' is not valid");
                }
                lo = number(r[0], min, max, names, what);
                hi = number(r[1], min, max, names, what);
                if (lo > hi) {
                    throw new IllegalArgumentException("cron " + what + " range '" + range + "' runs backwards");
                }
            } else {
                lo = number(range, min, max, names, what);
                hi = slash >= 0 ? max : lo;
            }
            for (int v = lo; v <= hi; v += step) {
                out.set(v);
            }
        }
        return out;
    }

    private static int number(String s, int min, int max, String[] names, String what) {
        String u = s.toUpperCase(Locale.ROOT);
        if (names != null) {
            for (int i = 0; i < names.length; i++) {
                if (names[i].equals(u)) {
                    return names == MONTHS ? i + 1 : i;
                }
            }
        }
        try {
            int v = Integer.parseInt(s);
            if (max > 0 && (v < min || v > max)) {
                throw new IllegalArgumentException("cron " + what + " value " + v + " is outside " + min + ".." + max);
            }
            return v;
        } catch (NumberFormatException e) {
            throw new IllegalArgumentException("cron " + what + " value '" + s + "' is not a number" + (names == null ? "" : " or a name"));
        }
    }

    public String expression() {
        return expression;
    }

    public ZoneId zone() {
        return zone;
    }

    /** The first fire time strictly after {@code after}, or null when the expression never matches (for example 31 February). */
    public Instant next(Instant after) {
        LocalDateTime t = LocalDateTime.ofInstant(after, zone).truncatedTo(ChronoUnit.MINUTES).plusMinutes(1);
        LocalDateTime limit = t.plusYears(SEARCH_YEARS);
        while (t.isBefore(limit)) {
            if (!months.get(t.getMonthValue())) {
                t = t.withDayOfMonth(1).toLocalDate().plusMonths(1).atStartOfDay();
                continue;
            }
            if (!dayMatches(t)) {
                t = t.toLocalDate().plusDays(1).atStartOfDay();
                continue;
            }
            if (!hours.get(t.getHour())) {
                t = t.truncatedTo(ChronoUnit.HOURS).plusHours(1);
                continue;
            }
            if (!minutes.get(t.getMinute())) {
                t = t.plusMinutes(1);
                continue;
            }
            Instant at = ZonedDateTime.ofLocal(t, zone, null).toInstant();
            if (at.isAfter(after)) {
                return at;
            }
            t = t.plusMinutes(1); // the second pass through a repeated hour
        }
        return null;
    }

    private boolean dayMatches(LocalDateTime t) {
        boolean dom = days.get(t.getDayOfMonth());
        boolean dow = weekdays.get(t.getDayOfWeek().getValue() % 7);
        if (dayRestricted && weekdayRestricted) {
            return dom || dow;
        }
        return dom && dow;
    }
}
