package tech.kayys.wayang.api;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Append-only audit log. There is deliberately no update or delete. */
public interface AuditStore {

    void append(AuditEntry entry);

    /** Newest first, at most {@code limit}, only entries strictly before {@code before} (null = from the newest). */
    List<AuditEntry> list(String tenant, int limit, Instant before);

    /** Bounded and per-process: for tests and the default memory store. Oldest entries fall off. */
    final class InMemory implements AuditStore {
        private final int perTenant;
        private final Map<String, Deque<AuditEntry>> byTenant = new HashMap<>();

        public InMemory(int perTenant) {
            this.perTenant = perTenant;
        }

        @Override
        public synchronized void append(AuditEntry e) {
            Deque<AuditEntry> d = byTenant.computeIfAbsent(e.tenant(), k -> new ArrayDeque<>());
            d.addFirst(e);
            while (d.size() > perTenant) {
                d.removeLast();
            }
        }

        @Override
        public synchronized List<AuditEntry> list(String tenant, int limit, Instant before) {
            List<AuditEntry> all = new ArrayList<>(byTenant.getOrDefault(tenant, new ArrayDeque<>()));
            all.sort((a, b) -> b.time().compareTo(a.time())); // stable: equal times keep newest-appended first
            List<AuditEntry> out = new ArrayList<>();
            for (AuditEntry e : all) {
                if (out.size() >= limit) {
                    break;
                }
                if (before == null || e.time().isBefore(before)) {
                    out.add(e);
                }
            }
            return out;
        }
    }
}
