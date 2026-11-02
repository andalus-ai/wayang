package tech.kayys.wayang.api.project;

import tech.kayys.wayang.api.ApiException;

import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

/** Tenant-scoped project persistence. */
public interface ProjectStore {

    Project create(String tenant, String id, String name, String description, String user, Instant now);

    Optional<Project> get(String tenant, String id);

    /** Newest first. */
    List<Project> list(String tenant);

    /** @return empty when the project does not exist */
    Optional<Project> update(String tenant, String id, String name, String description, Instant now);

    boolean delete(String tenant, String id);

    final class InMemory implements ProjectStore {
        private final Map<String, Map<String, Project>> byTenant = new LinkedHashMap<>();

        @Override
        public synchronized Project create(String tenant, String id, String name, String description, String user, Instant now) {
            Map<String, Project> m = byTenant.computeIfAbsent(tenant, t -> new LinkedHashMap<>());
            if (m.containsKey(id)) {
                throw new ApiException(ApiException.Code.CONFLICT, "Project '" + id + "' already exists");
            }
            Project p = new Project(tenant, id, name, description, user, now, now);
            m.put(id, p);
            return p;
        }

        @Override
        public synchronized Optional<Project> get(String tenant, String id) {
            return Optional.ofNullable(byTenant.getOrDefault(tenant, Map.of()).get(id));
        }

        @Override
        public synchronized List<Project> list(String tenant) {
            List<Project> out = new ArrayList<>(byTenant.getOrDefault(tenant, Map.of()).values());
            out.sort((a, b) -> b.createdAt().compareTo(a.createdAt()) != 0 ? b.createdAt().compareTo(a.createdAt()) : a.id().compareTo(b.id()));
            return out;
        }

        @Override
        public synchronized Optional<Project> update(String tenant, String id, String name, String description, Instant now) {
            Map<String, Project> m = byTenant.getOrDefault(tenant, Map.of());
            Project p = m.get(id);
            if (p == null) {
                return Optional.empty();
            }
            Project n = new Project(tenant, id, name, description, p.createdBy(), p.createdAt(), now);
            m.put(id, n);
            return Optional.of(n);
        }

        @Override
        public synchronized boolean delete(String tenant, String id) {
            return byTenant.getOrDefault(tenant, Map.of()).remove(id) != null;
        }
    }
}
