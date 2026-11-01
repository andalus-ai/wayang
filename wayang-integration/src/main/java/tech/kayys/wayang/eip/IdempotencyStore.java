package tech.kayys.wayang.eip;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Remembers processed message keys. Effectively-once processing = at-least-once
 * delivery + an idempotent receiver backed by a <em>durable, shared</em> store.
 * The bundled {@link InMemory} store is bounded but per-process only: it gives
 * no guarantee across restarts or replicas.
 */
public interface IdempotencyStore {

    /** @return true if the key was new (and is now recorded), false if already seen */
    boolean markIfNew(String key);

    final class InMemory implements IdempotencyStore {
        private final Map<String, Boolean> seen;

        public InMemory(int maxEntries) {
            this.seen = Collections.synchronizedMap(new LinkedHashMap<>(16, 0.75f, false) {
                private static final long serialVersionUID = 1L;

                @Override
                protected boolean removeEldestEntry(Map.Entry<String, Boolean> e) {
                    return size() > maxEntries;
                }
            });
        }

        @Override
        public boolean markIfNew(String key) {
            return seen.putIfAbsent(key, Boolean.TRUE) == null;
        }
    }
}
