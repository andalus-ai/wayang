package tech.kayys.wayang.eip;

import java.time.Clock;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * In-memory state shared by every pipeline load in this process: circuit breakers, throttle buckets, round-robin counters.
 * Pipelines are rebuilt for each node execution, so this state cannot live in the steps. It is per process and bounded: with
 * several replicas each has its own breaker and its own rate budget (document this in operations notes).
 */
public final class EipState {

    private static final int MAX_ENTRIES = 10_000;

    private final Map<String, Object> map = new LinkedHashMap<>();

    @SuppressWarnings("unchecked")
    synchronized <T> T get(String key, java.util.function.Supplier<T> create) {
        Object o = map.get(key);
        if (o == null) {
            o = create.get();
            map.put(key, o);
            while (map.size() > MAX_ENTRIES) {
                map.remove(map.keySet().iterator().next());
            }
        }
        return (T) o;
    }

    /** Consecutive-failure circuit breaker. */
    static final class Breaker {
        private int failures;
        private Instant openUntil;
        private boolean probing;

        /** @return true when a call may go ahead (closed, or the single half-open probe). */
        synchronized boolean tryAcquire(Instant now) {
            if (openUntil == null) {
                return true;
            }
            if (now.isBefore(openUntil)) {
                return false;
            }
            if (probing) {
                return false;
            }
            probing = true;
            return true;
        }

        synchronized void success() {
            failures = 0;
            openUntil = null;
            probing = false;
        }

        synchronized void failure(Instant now, int threshold, long openMillis) {
            probing = false;
            if (++failures >= threshold) {
                openUntil = now.plusMillis(openMillis);
            }
        }

        synchronized boolean isOpen(Instant now) {
            return openUntil != null && now.isBefore(openUntil);
        }
    }

    /** Token bucket. */
    static final class Bucket {
        private double tokens;
        private long lastMs;
        private boolean init;

        /** @return 0 when a token was taken, else the milliseconds until one is available. */
        synchronized long take(Clock clock, double perSecond, double burst) {
            long now = clock.millis();
            if (!init) {
                tokens = burst;
                lastMs = now;
                init = true;
            }
            tokens = Math.min(burst, tokens + (now - lastMs) * perSecond / 1000.0);
            lastMs = now;
            if (tokens >= 1) {
                tokens -= 1;
                return 0;
            }
            return (long) Math.ceil((1 - tokens) * 1000.0 / perSecond);
        }
    }

    static final class Counter {
        private long n;

        synchronized long next() {
            return n++;
        }
    }
}
