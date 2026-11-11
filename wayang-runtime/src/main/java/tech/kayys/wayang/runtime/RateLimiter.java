package tech.kayys.wayang.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A sliding-window limit per key (a chat, a sender): at most {@code max} events in the last {@code window}. Counting is in memory and per
 * process. With a {@link Journal} every allowed event is also written down, and a key's recent events are read back the first time it is seen,
 * so a restart does not hand everyone a fresh allowance; several replicas still each count their own (a replica reads the journal once per
 * key, not on every event). A journal that fails never blocks anyone: the limit then holds in memory only. At most {@value #MAX_KEYS} keys
 * are kept in memory; the least recently used goes first, so a flood of new keys cannot grow it without bound.
 */
public final class RateLimiter {

    static final int MAX_KEYS = 10_000;

    /** Where allowed events are written down so they survive a restart. Keys are opaque; implementations must not need to understand them. */
    public interface Journal {
        /** The events recorded for {@code key} in {@code scope} after {@code since}, in any order. */
        java.util.List<Instant> load(String scope, String key, Instant since);

        void record(String scope, String key, Instant at);

        /** Forgets everything recorded before {@code before} (all scopes). */
        void purgeBefore(Instant before);
    }

    /** A journal that lives in this process: for tests, and to show the contract (a restart of the limiter, not of the JVM, keeps counts). */
    public static final class MemoryJournal implements Journal {
        private final java.util.List<String[]> rows = new java.util.ArrayList<>();
        private final java.util.List<Instant> times = new java.util.ArrayList<>();

        @Override
        public synchronized java.util.List<Instant> load(String scope, String key, Instant since) {
            java.util.List<Instant> out = new java.util.ArrayList<>();
            for (int i = 0; i < rows.size(); i++) {
                if (rows.get(i)[0].equals(scope) && rows.get(i)[1].equals(key) && times.get(i).isAfter(since)) {
                    out.add(times.get(i));
                }
            }
            return out;
        }

        @Override
        public synchronized void record(String scope, String key, Instant at) {
            rows.add(new String[] { scope, key });
            times.add(at);
        }

        @Override
        public synchronized void purgeBefore(Instant before) {
            for (int i = rows.size() - 1; i >= 0; i--) {
                if (times.get(i).isBefore(before)) {
                    rows.remove(i);
                    times.remove(i);
                }
            }
        }

        public synchronized int size() {
            return rows.size();
        }
    }

    private Journal journal;
    private String scope = "";

    private final int[] max;
    private final Duration[] windows;
    private final Duration longest;
    private final Map<String, ArrayDeque<Instant>> seen = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Instant>> e) {
            return size() > MAX_KEYS;
        }
    };

    public RateLimiter(int max, Duration window) {
        this(new int[] {max}, new Duration[] {window});
    }

    /** Several limits at once (for example per hour and per day): an event is allowed only when it is within every one. */
    public RateLimiter(int[] max, Duration[] windows) {
        if (max.length == 0 || max.length != windows.length) {
            throw new IllegalArgumentException("a rate limit needs at least one limit");
        }
        Duration longest = windows[0];
        for (int i = 0; i < max.length; i++) {
            if (max[i] < 1 || windows[i].isZero() || windows[i].isNegative()) {
                throw new IllegalArgumentException("a rate limit needs at least 1 event and a positive window");
            }
            if (windows[i].compareTo(longest) > 0) {
                longest = windows[i];
            }
        }
        this.max = max.clone();
        this.windows = windows.clone();
        this.longest = longest;
    }

    /** Writes allowed events to {@code journal} under {@code scope} and reads them back per key. Call before use. */
    public RateLimiter withJournal(Journal journal, String scope) {
        this.journal = journal;
        this.scope = scope;
        return this;
    }

    /** The longest window: the journal never needs to keep events older than this. */
    public Duration longestWindow() {
        return longest;
    }

    /** @return true and counts the event, or false (not counted) when {@code key} is over any of its limits */
    public synchronized boolean allow(String key, Instant now) {
        ArrayDeque<Instant> q = seen.get(key);
        if (q == null) {
            q = new ArrayDeque<>();
            if (journal != null) {
                try {
                    journal.load(scope, key, now.minus(longest)).stream().sorted().forEach(q::addLast);
                } catch (RuntimeException e) {
                    // the journal is unavailable: this key is counted from now, in memory
                }
            }
            seen.put(key, q);
        }
        Instant oldest = now.minus(longest);
        while (!q.isEmpty() && !q.peekFirst().isAfter(oldest)) {
            q.pollFirst();
        }
        for (int i = 0; i < max.length; i++) {
            Instant cutoff = now.minus(windows[i]);
            long inWindow = q.stream().filter(t -> t.isAfter(cutoff)).count();
            if (inWindow >= max[i]) {
                return false;
            }
        }
        q.addLast(now);
        if (journal != null) {
            try {
                journal.record(scope, key, now);
            } catch (RuntimeException e) {
                // never block a message because the journal is down
            }
        }
        return true;
    }

    /**
     * Reads the {@code rateLimit: {perHour: N, perDay: M}} setting of a trigger (one or both, each a whole number from 1 to 10000), or null
     * when there is none. A day limit bounds what a chat can cost even when its hourly rate is allowed. @throws IllegalArgumentException when it is malformed
     */
    public static RateLimiter fromTriggerConfig(Map<String, Object> triggerConfig) {
        Object r = triggerConfig.get("rateLimit");
        if (r == null) {
            return null;
        }
        String problem = "rateLimit must be {\"perHour\": N} and/or {\"perDay\": N}, each a whole number from 1 to 10000";
        if (!(r instanceof Map<?, ?> m) || m.isEmpty() || m.size() > 2 || !java.util.Set.of("perHour", "perDay").containsAll(m.keySet())) {
            throw new IllegalArgumentException(problem);
        }
        java.util.List<Integer> maxes = new java.util.ArrayList<>();
        java.util.List<Duration> windows = new java.util.ArrayList<>();
        for (var e : java.util.Map.of("perHour", Duration.ofHours(1), "perDay", Duration.ofDays(1)).entrySet()) {
            if (m.containsKey(e.getKey())) {
                if (!(m.get(e.getKey()) instanceof Number n) || n.longValue() < 1 || n.longValue() > 10_000 || n.doubleValue() != n.longValue()) {
                    throw new IllegalArgumentException(problem);
                }
                maxes.add((int) n.longValue());
                windows.add(e.getValue());
            }
        }
        return new RateLimiter(maxes.stream().mapToInt(Integer::intValue).toArray(), windows.toArray(new Duration[0]));
    }
}
