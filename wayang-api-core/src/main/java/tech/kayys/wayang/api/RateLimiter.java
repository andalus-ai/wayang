package tech.kayys.wayang.api;

import tech.kayys.wayang.api.definition.Studio;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * A fixed-window limit per caller, for the few endpoints that make outbound calls on a designer's behalf. In memory and per process:
 * with several replicas the effective limit is the limit times the replicas, which is fine for protecting third parties from a runaway
 * Studio tab but is not a quota. The table of callers is bounded; when full the oldest windows are dropped.
 */
public final class RateLimiter {

    private record Window(long startMs, int count) {
    }

    private final int limit;
    private final long windowMs;
    private final Clock clock;
    private final int maxCallers;
    private final Map<String, Window> windows = new LinkedHashMap<>();

    public RateLimiter(int limit, long windowMs, Clock clock) {
        this(limit, windowMs, clock, 10_000);
    }

    RateLimiter(int limit, long windowMs, Clock clock, int maxCallers) {
        if (limit < 1 || windowMs < 1) {
            throw new IllegalArgumentException("limit and window must be positive");
        }
        this.limit = limit;
        this.windowMs = windowMs;
        this.clock = clock;
        this.maxCallers = maxCallers;
    }

    /** Counts one call for [p]; throws {@link ApiException} (429) when the window is used up. */
    public synchronized void check(Principal p) {
        String key = p.tenantId() + '\0' + p.userId();
        long now = clock.millis();
        Window w = windows.get(key);
        if (w == null || now - w.startMs() >= windowMs || now < w.startMs()) {
            windows.remove(key);
            windows.put(key, new Window(now, 1));
            while (windows.size() > maxCallers) {
                windows.remove(windows.keySet().iterator().next());
            }
            return;
        }
        if (w.count() >= limit) {
            long wait = Math.max(1, (w.startMs() + windowMs - now + 999) / 1000);
            throw new ApiException(ApiException.Code.TOO_MANY_REQUESTS, "Too many calls: try again in " + wait + " s");
        }
        windows.put(key, new Window(w.startMs(), w.count() + 1));
    }
}
