package tech.kayys.wayang.api.project;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import tech.kayys.wayang.agent.InstalledAgent;

/**
 * Tenant- and project-scoped persistence for installed agent definitions.
 * Callers must authorize project access before invoking this store.
 */
public interface InstalledAgentStore {

    InstalledAgent save(InstalledAgent installedAgent);

    Optional<InstalledAgent> get(String tenantId, String projectId, String installationId);

    List<InstalledAgent> list(String tenantId, String projectId);

    boolean delete(String tenantId, String projectId, String installationId);

    final class InMemory implements InstalledAgentStore {
        private final Map<Key, InstalledAgent> entries = new LinkedHashMap<>();

        @Override
        public synchronized InstalledAgent save(InstalledAgent installedAgent) {
            Key key = key(installedAgent.tenantId(), installedAgent.projectId(), installedAgent.installationId());
            InstalledAgent previous = entries.get(key);
            if (previous != null && !installedAgent.lock().packageId().equals(previous.lock().packageId())) {
                throw new IllegalArgumentException("An installation cannot change package identity");
            }
            if (previous != null && !installedAgent.installedAt().equals(previous.installedAt())) {
                throw new IllegalArgumentException("An installation timestamp is immutable");
            }
            if (previous != null && installedAgent.updatedAt().isBefore(previous.updatedAt())) {
                throw new IllegalArgumentException("An installation update cannot move backwards in time");
            }
            entries.put(key, installedAgent);
            return installedAgent;
        }

        @Override
        public synchronized Optional<InstalledAgent> get(String tenantId, String projectId, String installationId) {
            return Optional.ofNullable(entries.get(key(tenantId, projectId, installationId)));
        }

        @Override
        public synchronized List<InstalledAgent> list(String tenantId, String projectId) {
            List<InstalledAgent> result = new ArrayList<>();
            entries.forEach((key, value) -> {
                if (key.tenantId().equals(tenantId) && key.projectId().equals(projectId)) result.add(value);
            });
            result.sort(Comparator.comparing(InstalledAgent::installationId));
            return List.copyOf(result);
        }

        @Override
        public synchronized boolean delete(String tenantId, String projectId, String installationId) {
            return entries.remove(key(tenantId, projectId, installationId)) != null;
        }

        private static Key key(String tenantId, String projectId, String installationId) {
            if (tenantId == null || tenantId.isBlank() || projectId == null || projectId.isBlank()
                    || installationId == null || installationId.isBlank()) {
                throw new IllegalArgumentException("tenantId, projectId and installationId are required");
            }
            return new Key(tenantId, projectId, installationId);
        }
    }

    record Key(String tenantId, String projectId, String installationId) { }
}
