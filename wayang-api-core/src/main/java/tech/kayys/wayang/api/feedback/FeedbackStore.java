package tech.kayys.wayang.api.feedback;

import java.time.Instant;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Feedback log per tenant: append, read, and drop what is older than a given time. Entries are never edited. */
public interface FeedbackStore {

    void append(FeedbackEntry entry);

    /** Newest first, at most {@code limit}, strictly before {@code before} (null = from the newest), only {@code kind} when it is not null. */
    List<FeedbackEntry> list(String tenant, String kind, int limit, Instant before);

    /** Removes this tenant's entries strictly older than {@code before}; returns how many went. */
    int purgeBefore(String tenant, Instant before);

    /** Bounded and per-process: for tests and the default memory store. Oldest entries fall off. */
    final class InMemory implements FeedbackStore {
        private final int perTenant;
        private final Map<String, Deque<FeedbackEntry>> byTenant = new HashMap<>();

        public InMemory(int perTenant) {
            this.perTenant = perTenant;
        }

        @Override
        public synchronized void append(FeedbackEntry e) {
            Deque<FeedbackEntry> d = byTenant.computeIfAbsent(e.tenant(), k -> new ArrayDeque<>());
            d.addFirst(e);
            while (d.size() > perTenant) {
                d.removeLast();
            }
        }

        @Override
        public synchronized int purgeBefore(String tenant, Instant before) {
            Deque<FeedbackEntry> d = byTenant.get(tenant);
            if (d == null) {
                return 0;
            }
            int n = d.size();
            d.removeIf(e -> e.time().isBefore(before));
            return n - d.size();
        }

        @Override
        public synchronized List<FeedbackEntry> list(String tenant, String kind, int limit, Instant before) {
            List<FeedbackEntry> all = new ArrayList<>(byTenant.getOrDefault(tenant, new ArrayDeque<>()));
            all.sort((a, b) -> b.time().compareTo(a.time()));
            List<FeedbackEntry> out = new ArrayList<>();
            for (FeedbackEntry e : all) {
                if (out.size() >= limit) {
                    break;
                }
                if ((kind == null || kind.equals(e.kind())) && (before == null || e.time().isBefore(before))) {
                    out.add(e);
                }
            }
            return out;
        }
    }
}
