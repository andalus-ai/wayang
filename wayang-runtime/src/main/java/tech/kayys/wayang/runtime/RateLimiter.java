package tech.kayys.wayang.runtime;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayDeque;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A sliding-window limit per key (a chat, a sender): at most {@code max} events in the last {@code window}. In memory and per process, so a
 * restart forgets it and several replicas each count on their own. At most {@value #MAX_KEYS} keys are kept; the least recently used goes first,
 * so a flood of new keys cannot grow it without bound.
 */
public final class RateLimiter {

    static final int MAX_KEYS = 10_000;

    private final int max;
    private final Duration window;
    private final Map<String, ArrayDeque<Instant>> seen = new LinkedHashMap<>(16, 0.75f, true) {
        private static final long serialVersionUID = 1L;

        @Override
        protected boolean removeEldestEntry(Map.Entry<String, ArrayDeque<Instant>> e) {
            return size() > MAX_KEYS;
        }
    };

    public RateLimiter(int max, Duration window) {
        if (max < 1 || window.isZero() || window.isNegative()) {
            throw new IllegalArgumentException("a rate limit needs at least 1 event and a positive window");
        }
        this.max = max;
        this.window = window;
    }

    /** @return true and counts the event, or false (not counted) when {@code key} is over its limit */
    public synchronized boolean allow(String key, Instant now) {
        ArrayDeque<Instant> q = seen.computeIfAbsent(key, k -> new ArrayDeque<>());
        Instant cutoff = now.minus(window);
        while (!q.isEmpty() && !q.peekFirst().isAfter(cutoff)) {
            q.pollFirst();
        }
        if (q.size() >= max) {
            return false;
        }
        q.addLast(now);
        return true;
    }

    /** Reads the {@code rateLimit: {perHour: N}} setting of a trigger, or null when there is none. @throws IllegalArgumentException when it is malformed */
    public static RateLimiter fromTriggerConfig(Map<String, Object> triggerConfig) {
        Object r = triggerConfig.get("rateLimit");
        if (r == null) {
            return null;
        }
        if (!(r instanceof Map<?, ?> m) || m.size() != 1 || !(m.get("perHour") instanceof Number n)
                || n.longValue() < 1 || n.longValue() > 10_000 || n.doubleValue() != n.longValue()) {
            throw new IllegalArgumentException("rateLimit must be {\"perHour\": a whole number from 1 to 10000}");
        }
        return new RateLimiter((int) n.longValue(), Duration.ofHours(1));
    }
}
