package tech.kayys.wayang.eip;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Durable state of the {@code collect} step (an Aggregator across separate pipeline runs). {@link #append} adds one message
 * to its group and returns the whole group; {@link #claim} closes a group exactly once: of several replicas that all see
 * a complete group, only one {@code claim} returns true, so the aggregate is emitted once.
 */
public interface AggregationStore {

    record Entry(long seq, long createdMs, String json) {
    }

    /** @throws EipException when the group would exceed {@code maxEntries} */
    List<Entry> append(String namespace, String key, String json, long nowMs, int maxEntries);

    /** Deletes entries up to and including {@code upToSeq}; true when this call removed at least one. */
    boolean claim(String namespace, String key, long upToSeq);

    /** One waiting group: its key and its messages, oldest first. */
    record Group(String key, List<Entry> entries) {
    }

    /** Namespaces that currently hold at least one group. */
    java.util.Set<String> namespaces();

    /** Groups of {@code namespace} whose oldest message was created before {@code cutoffMs}, oldest first, at most {@code limit}. */
    List<Group> expired(String namespace, long cutoffMs, int limit);

    /**
     * Drops entries created before {@code cutoffMs} (groups that never completed), so abandoned groups cannot pile up forever.
     * Returns how many entries were removed.
     */
    int purge(long cutoffMs);

    final class InMemory implements AggregationStore {
        private final int maxGroups;
        private final Map<String, List<Entry>> groups = new LinkedHashMap<>();
        private long seq;

        public InMemory(int maxGroups) {
            this.maxGroups = maxGroups;
        }

        @Override
        public synchronized List<Entry> append(String ns, String key, String json, long nowMs, int maxEntries) {
            String k = ns + '\0' + key;
            List<Entry> g = groups.computeIfAbsent(k, x -> new ArrayList<>());
            if (g.size() >= maxEntries) {
                throw new EipException("collect group '" + key + "' exceeds " + maxEntries + " messages");
            }
            g.add(new Entry(++seq, nowMs, json));
            while (groups.size() > maxGroups) {
                groups.remove(groups.keySet().iterator().next());
            }
            return List.copyOf(g);
        }

        @Override
        public synchronized java.util.Set<String> namespaces() {
            java.util.Set<String> out = new java.util.TreeSet<>();
            groups.keySet().forEach(k -> out.add(k.substring(0, k.indexOf('\0'))));
            return out;
        }

        @Override
        public synchronized List<Group> expired(String ns, long cutoffMs, int limit) {
            List<Group> out = new ArrayList<>();
            String prefix = ns + '\0';
            groups.entrySet().stream()
                    .filter(e -> e.getKey().startsWith(prefix) && !e.getValue().isEmpty() && e.getValue().get(0).createdMs() < cutoffMs)
                    .sorted(java.util.Comparator.comparingLong(e -> e.getValue().get(0).createdMs()))
                    .limit(limit)
                    .forEach(e -> out.add(new Group(e.getKey().substring(prefix.length()), List.copyOf(e.getValue()))));
            return out;
        }

        @Override
        public synchronized int purge(long cutoffMs) {
            int removed = 0;
            for (List<Entry> g : groups.values()) {
                int before = g.size();
                g.removeIf(e -> e.createdMs() < cutoffMs);
                removed += before - g.size();
            }
            groups.values().removeIf(List::isEmpty);
            return removed;
        }

        @Override
        public synchronized boolean claim(String ns, String key, long upTo) {
            List<Entry> g = groups.get(ns + '\0' + key);
            if (g == null) {
                return false;
            }
            boolean any = g.removeIf(e -> e.seq() <= upTo);
            if (g.isEmpty()) {
                groups.remove(ns + '\0' + key);
            }
            return any;
        }
    }
}
