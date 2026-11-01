package tech.kayys.wayang.eip;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Optional;

/**
 * Claim Check storage: park a big or sensitive body, pass a token. Every call is scoped by a namespace
 * (tenant, process, node) so a token from one tenant is worthless in another. Bodies are JSON-like values.
 */
public interface ClaimStore {

    void put(String namespace, String token, Object body, Instant expires);

    /** Empty when unknown or expired. */
    Optional<Object> get(String namespace, String token, Instant now);

    void delete(String namespace, String token);

    /** Bounded, per-process, forgets on restart. Use the JDBC store when a claim must outlive the process. */
    final class InMemory implements ClaimStore {
        private record Entry(Object body, Instant expires) {
        }

        private final int max;
        private final Map<String, Entry> map = new LinkedHashMap<>();

        public InMemory(int maxEntries) {
            this.max = maxEntries;
        }

        @Override
        public synchronized void put(String ns, String token, Object body, Instant expires) {
            map.put(ns + '\0' + token, new Entry(body, expires));
            while (map.size() > max) {
                map.remove(map.keySet().iterator().next());
            }
        }

        @Override
        public synchronized Optional<Object> get(String ns, String token, Instant now) {
            Entry e = map.get(ns + '\0' + token);
            if (e == null || !e.expires().isAfter(now)) {
                map.remove(ns + '\0' + token);
                return Optional.empty();
            }
            return Optional.ofNullable(e.body());
        }

        @Override
        public synchronized void delete(String ns, String token) {
            map.remove(ns + '\0' + token);
        }
    }
}
