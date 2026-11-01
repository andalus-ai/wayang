package tech.kayys.wayang.api;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface OAuthStore {

    /** A pending authorisation, bound to tenant and connection. Single use. */
    record State(String state, String tenant, String connectionId, String verifier, Instant expiresAt) {
    }

    /** @throws ApiException CONFLICT when the id exists */
    void create(OAuthConnection c);

    Optional<OAuthConnection> get(String tenant, String id);

    List<OAuthConnection> list(String tenant);

    void update(OAuthConnection c);

    boolean delete(String tenant, String id);

    void saveState(State s);

    /** Atomically removes and returns the state; empty when unknown, already used or expired. */
    Optional<State> takeState(String state, Instant now);

    final class InMemory implements OAuthStore {
        private final Map<String, OAuthConnection> conns = new LinkedHashMap<>();
        private final Map<String, State> states = new LinkedHashMap<>();

        private static String key(String t, String id) {
            return t + "\u0000" + id;
        }

        @Override
        public synchronized void create(OAuthConnection c) {
            if (conns.putIfAbsent(key(c.tenant(), c.id()), c) != null) {
                throw new ApiException(ApiException.Code.CONFLICT, "Connection '" + c.id() + "' already exists");
            }
        }

        @Override
        public synchronized Optional<OAuthConnection> get(String tenant, String id) {
            return Optional.ofNullable(conns.get(key(tenant, id)));
        }

        @Override
        public synchronized List<OAuthConnection> list(String tenant) {
            List<OAuthConnection> out = new ArrayList<>();
            conns.values().forEach(c -> {
                if (c.tenant().equals(tenant)) {
                    out.add(c);
                }
            });
            return out;
        }

        @Override
        public synchronized void update(OAuthConnection c) {
            conns.replace(key(c.tenant(), c.id()), c);
        }

        @Override
        public synchronized boolean delete(String tenant, String id) {
            states.values().removeIf(s -> s.tenant().equals(tenant) && s.connectionId().equals(id));
            return conns.remove(key(tenant, id)) != null;
        }

        @Override
        public synchronized void saveState(State s) {
            states.put(s.state(), s);
        }

        @Override
        public synchronized Optional<State> takeState(String state, Instant now) {
            State s = states.remove(state);
            return s == null || !s.expiresAt().isAfter(now) ? Optional.empty() : Optional.of(s);
        }
    }
}
