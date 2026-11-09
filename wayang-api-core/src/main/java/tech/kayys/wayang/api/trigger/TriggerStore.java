package tech.kayys.wayang.api.trigger;

import tech.kayys.wayang.api.ApiException;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

public interface TriggerStore {

    /** @throws ApiException CONFLICT when the id exists */
    void create(Trigger t);

    Optional<Trigger> get(String tenant, String id);

    List<Trigger> list(String tenant);

    /** All enabled POLL and SCHEDULE triggers of all tenants (the scheduler's work list). */
    List<Trigger> pollable();

    void update(Trigger t);

    boolean delete(String tenant, String id);

    final class InMemory implements TriggerStore {
        private final Map<String, Trigger> all = new LinkedHashMap<>();

        private static String key(String tenant, String id) {
            return tenant + "\u0000" + id;
        }

        @Override
        public synchronized void create(Trigger t) {
            if (all.putIfAbsent(key(t.tenant(), t.id()), t) != null) {
                throw new ApiException(ApiException.Code.CONFLICT, "Trigger '" + t.id() + "' already exists");
            }
        }

        @Override
        public synchronized Optional<Trigger> get(String tenant, String id) {
            return Optional.ofNullable(all.get(key(tenant, id)));
        }

        @Override
        public synchronized List<Trigger> list(String tenant) {
            List<Trigger> out = new ArrayList<>();
            all.values().forEach(t -> {
                if (t.tenant().equals(tenant)) {
                    out.add(t);
                }
            });
            return out;
        }

        @Override
        public synchronized List<Trigger> pollable() {
            return all.values().stream().filter(t -> t.enabled() && t.kind() != Trigger.Kind.WEBHOOK).toList();
        }

        @Override
        public synchronized void update(Trigger t) {
            all.replace(key(t.tenant(), t.id()), t);
        }

        @Override
        public synchronized boolean delete(String tenant, String id) {
            return all.remove(key(tenant, id)) != null;
        }
    }
}
