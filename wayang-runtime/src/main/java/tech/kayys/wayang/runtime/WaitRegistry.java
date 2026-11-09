package tech.kayys.wayang.runtime;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Tracks pending waits so an external completion (form submitted, timer fired,
 * message correlated) can be routed back to the parked node.
 *
 * <p>
 * Production deployments must back this with a durable, shared store (database,
 * Redis): that is what lets a restarted node re-attach to the existing ticket
 * instead of creating a duplicate human task. {@link InMemory} is for tests and
 * single-node demos only.
 *
 * <p>
 * Contract for implementations: {@link #register} is idempotent per
 * {@code (runId, nodeId)} - registering again returns the existing ticket;
 * {@link #take} is atomic (at most one caller gets the ticket).
 */
public interface WaitRegistry {

    /** Stores the ticket, unless a ticket for the same run+node exists, which is returned instead. */
    WaitTicket register(String runId, WaitTicket ticket);

    /** Removes and returns the ticket together with the run it belongs to. */
    Optional<Registered> take(String token);

    Optional<Registered> find(String token);

    Optional<Registered> findByNode(String runId, String nodeId);

    /** Timer tickets whose {@code dueAt} is not after {@code now}. */
    List<Registered> due(Instant now);

    /** Pending waits of one kind ({@code human}, {@code timer}, {@code message}), oldest first, at most {@code limit}. */
    List<Registered> listByKind(String kind, int limit);

    /** Pending waits of one run. */
    List<Registered> listByRun(String runId);

    /** A message wait matching name and correlation key (oldest first). */
    Optional<Registered> findMessage(String name, String correlationKey);

    /** Keeps a message that arrived before anyone waited for it. */
    void bufferMessage(String name, String correlationKey, Map<String, Object> payload);

    /** Consumes a buffered message (first in, first out). */
    Optional<Map<String, Object>> takeBuffered(String name, String correlationKey);

    record Registered(String runId, WaitTicket ticket) {
    }

    /**
     * The correlation key as the registry stores it: private to the tenant, so one tenant's message can never be delivered to, or buffered for,
     * another tenant's run with the same message name and key. No tenant (a standalone runtime) leaves the key as it is.
     */
    static String scoped(String tenant, String correlationKey) {
        return tenant == null || tenant.isEmpty() ? correlationKey : tenant + "\u001f" + correlationKey;
    }

    /** The key as the person wrote it (what to show in views). */
    static String unscoped(String storedKey) {
        int i = storedKey == null ? -1 : storedKey.indexOf('\u001f');
        return i < 0 ? storedKey : storedKey.substring(i + 1);
    }

    final class InMemory implements WaitRegistry {
        private static final int MAX_BUFFERED_PER_KEY = 100;

        private final Map<String, Registered> pending = new ConcurrentHashMap<>();
        private final Map<String, List<Map<String, Object>>> buffered = new LinkedHashMap<>();

        @Override
        public synchronized WaitTicket register(String runId, WaitTicket ticket) {
            for (Registered r : pending.values()) {
                if (r.runId().equals(runId) && r.ticket().nodeId().equals(ticket.nodeId())) {
                    return r.ticket();
                }
            }
            pending.put(ticket.token(), new Registered(runId, ticket));
            return ticket;
        }

        @Override
        public Optional<Registered> take(String token) {
            return Optional.ofNullable(pending.remove(token));
        }

        @Override
        public Optional<Registered> find(String token) {
            return Optional.ofNullable(pending.get(token));
        }

        @Override
        public Optional<Registered> findByNode(String runId, String nodeId) {
            return pending.values().stream()
                    .filter(r -> r.runId().equals(runId) && r.ticket().nodeId().equals(nodeId)).findFirst();
        }

        @Override
        public List<Registered> due(Instant now) {
            List<Registered> out = new ArrayList<>();
            for (Registered r : pending.values()) {
                Instant d = r.ticket().dueAt();
                if ("timer".equals(r.ticket().kind()) && d != null && !d.isAfter(now)) {
                    out.add(r);
                }
            }
            out.sort(java.util.Comparator.comparing(r -> r.ticket().dueAt()));
            return out;
        }

        @Override
        public List<Registered> listByRun(String runId) {
            return pending.values().stream().filter(r -> r.runId().equals(runId)).toList();
        }

        @Override
        public List<Registered> listByKind(String kind, int limit) {
            List<Registered> out = new ArrayList<>();
            for (Registered r : pending.values()) {
                if (r.ticket().kind().equals(kind)) {
                    out.add(r);
                }
            }
            out.sort(java.util.Comparator.comparing(r -> r.ticket().token()));
            return out.size() > limit ? new ArrayList<>(out.subList(0, limit)) : out;
        }

        @Override
        public Optional<Registered> findMessage(String name, String correlationKey) {
            return pending.values().stream().filter(r -> "message".equals(r.ticket().kind())
                    && name.equals(r.ticket().messageName()) && java.util.Objects.equals(correlationKey, r.ticket().correlationKey()))
                    .findFirst();
        }

        @Override
        public synchronized void bufferMessage(String name, String correlationKey, Map<String, Object> payload) {
            List<Map<String, Object>> l = buffered.computeIfAbsent(name + "\u001f" + correlationKey, k -> new ArrayList<>());
            if (l.size() >= MAX_BUFFERED_PER_KEY) {
                throw new IllegalStateException("Too many undelivered messages for " + name + "/" + correlationKey);
            }
            l.add(Map.copyOf(payload));
        }

        @Override
        public synchronized Optional<Map<String, Object>> takeBuffered(String name, String correlationKey) {
            String k = name + "\u001f" + correlationKey;
            List<Map<String, Object>> l = buffered.get(k);
            if (l == null || l.isEmpty()) {
                return Optional.empty();
            }
            Map<String, Object> m = l.remove(0);
            if (l.isEmpty()) {
                buffered.remove(k);
            }
            return Optional.of(m);
        }

        public int size() {
            return pending.size();
        }
    }
}
