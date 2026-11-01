package tech.kayys.wayang.runtime;

import java.util.List;
import java.util.Map;

/**
 * Pulls new events from an external source (a chat, a mailbox) for a trigger. Stateless: the caller owns the cursor
 * and persists it <em>after</em> the events were handed over, so delivery is at-least-once.
 */
@FunctionalInterface
public interface Poller {

    /**
     * @param config static trigger configuration, secrets already resolved
     * @param cursor opaque position returned by the previous call; null the first time
     */
    Result poll(Map<String, Object> config, String cursor) throws Exception;

    /**
     * @param events      each becomes the input of one run; keep them small and flat
     * @param nextCursor  where to continue; null = unchanged
     */
    record Result(List<Map<String, Object>> events, String nextCursor) {
        public Result {
            events = events == null ? List.of() : List.copyOf(events);
        }
    }
}
