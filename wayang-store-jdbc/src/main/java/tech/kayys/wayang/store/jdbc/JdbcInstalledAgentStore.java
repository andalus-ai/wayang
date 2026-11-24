package tech.kayys.wayang.store.jdbc;

import java.sql.Connection;
import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import javax.sql.DataSource;

import tech.kayys.wayang.agent.AgentPackageLock;
import tech.kayys.wayang.agent.InstalledAgent;
import tech.kayys.wayang.api.project.InstalledAgentStore;
import tech.kayys.wayang.runtime.json.Json;

/** PostgreSQL persistence for project-scoped installed agent definitions and exact package locks. */
public final class JdbcInstalledAgentStore implements InstalledAgentStore {

    private final DataSource dataSource;

    public JdbcInstalledAgentStore(DataSource dataSource) {
        this.dataSource = dataSource;
    }

    @Override
    public InstalledAgent save(InstalledAgent installedAgent) {
        String document = Json.write(toDocument(installedAgent));
        String sql = """
                INSERT INTO wayang_installed_agent
                    (tenant_id, project_id, installation_id, package_id, package_version, artifact_sha256, lock_sha256, record_json, installed_ms, updated_ms)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                ON CONFLICT (tenant_id, project_id, installation_id) DO UPDATE SET
                    package_id = EXCLUDED.package_id,
                    package_version = EXCLUDED.package_version,
                    artifact_sha256 = EXCLUDED.artifact_sha256,
                    lock_sha256 = EXCLUDED.lock_sha256,
                    record_json = EXCLUDED.record_json,
                    installed_ms = EXCLUDED.installed_ms,
                    updated_ms = EXCLUDED.updated_ms
                WHERE wayang_installed_agent.updated_ms <= EXCLUDED.updated_ms
                  AND wayang_installed_agent.package_id = EXCLUDED.package_id
                """;
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, installedAgent.tenantId());
            statement.setString(2, installedAgent.projectId());
            statement.setString(3, installedAgent.installationId());
            statement.setString(4, installedAgent.lock().packageId());
            statement.setString(5, installedAgent.lock().version());
            statement.setString(6, installedAgent.lock().artifactSha256());
            statement.setString(7, installedAgent.lock().sha256());
            statement.setString(8, document);
            statement.setLong(9, installedAgent.installedAt().toEpochMilli());
            statement.setLong(10, installedAgent.updatedAt().toEpochMilli());
            if (statement.executeUpdate() == 0) {
                throw new IllegalArgumentException("A stale installed agent update cannot replace a newer record");
            }
            return installedAgent;
        } catch (SQLException e) {
            throw failure("save", e);
        }
    }

    @Override
    public Optional<InstalledAgent> get(String tenantId, String projectId, String installationId) {
        String sql = "SELECT record_json FROM wayang_installed_agent WHERE tenant_id = ? AND project_id = ? AND installation_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, projectId);
            statement.setString(3, installationId);
            try (ResultSet rs = statement.executeQuery()) {
                return rs.next() ? Optional.of(fromDocument(Json.read(rs.getString(1)))) : Optional.empty();
            }
        } catch (SQLException | RuntimeException e) {
            throw failure("get", e);
        }
    }

    @Override
    public List<InstalledAgent> list(String tenantId, String projectId) {
        String sql = "SELECT record_json FROM wayang_installed_agent WHERE tenant_id = ? AND project_id = ? ORDER BY installation_id";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, projectId);
            List<InstalledAgent> result = new ArrayList<>();
            try (ResultSet rs = statement.executeQuery()) {
                while (rs.next()) result.add(fromDocument(Json.read(rs.getString(1))));
            }
            return List.copyOf(result);
        } catch (SQLException | RuntimeException e) {
            throw failure("list", e);
        }
    }

    @Override
    public boolean delete(String tenantId, String projectId, String installationId) {
        String sql = "DELETE FROM wayang_installed_agent WHERE tenant_id = ? AND project_id = ? AND installation_id = ?";
        try (Connection connection = dataSource.getConnection(); PreparedStatement statement = connection.prepareStatement(sql)) {
            statement.setString(1, tenantId);
            statement.setString(2, projectId);
            statement.setString(3, installationId);
            return statement.executeUpdate() > 0;
        } catch (SQLException e) {
            throw failure("delete", e);
        }
    }

    private static Map<String, Object> toDocument(InstalledAgent installed) {
        Map<String, Object> root = new LinkedHashMap<>();
        root.put("tenantId", installed.tenantId());
        root.put("projectId", installed.projectId());
        root.put("installationId", installed.installationId());
        root.put("installedAt", installed.installedAt().toString());
        root.put("updatedAt", installed.updatedAt().toString());
        root.put("requestedPermissions", installed.requestedPermissions());

        AgentPackageLock lock = installed.lock();
        Map<String, Object> lockDocument = new LinkedHashMap<>();
        lockDocument.put("packageId", lock.packageId());
        lockDocument.put("version", lock.version());
        lockDocument.put("artifactSha256", lock.artifactSha256());
        lockDocument.put("sha256", lock.sha256());
        lockDocument.put("dependencies", lock.dependencies().stream().map(dependency -> Map.of(
                "kind", dependency.kind(), "packageId", dependency.packageId(),
                "version", dependency.version(), "artifactSha256", dependency.artifactSha256())).toList());
        root.put("lock", lockDocument);

        Map<String, Object> bindings = new LinkedHashMap<>();
        installed.configurationBindings().forEach((id, binding) -> bindings.put(id,
                Map.of("kind", binding.kind(), "reference", binding.reference())));
        root.put("configurationBindings", bindings);
        return root;
    }

    private static InstalledAgent fromDocument(Object value) {
        Map<String, Object> root = object(value, "installed agent");
        Map<String, Object> rawLock = object(root.get("lock"), "lock");
        List<AgentPackageLock.LockedDependency> dependencies = new ArrayList<>();
        for (Object raw : list(rawLock.get("dependencies"), "lock.dependencies")) {
            Map<String, Object> item = object(raw, "lock.dependencies[]");
            dependencies.add(new AgentPackageLock.LockedDependency(string(item, "kind"),
                    string(item, "packageId"), string(item, "version"), string(item, "artifactSha256")));
        }
        AgentPackageLock lock = new AgentPackageLock(string(rawLock, "packageId"), string(rawLock, "version"),
                string(rawLock, "artifactSha256"), dependencies, string(rawLock, "sha256"));

        Map<String, InstalledAgent.ConfigurationBinding> bindings = new LinkedHashMap<>();
        object(root.get("configurationBindings"), "configurationBindings").forEach((id, raw) -> {
            Map<String, Object> binding = object(raw, "configuration binding");
            bindings.put(id, new InstalledAgent.ConfigurationBinding(string(binding, "kind"), string(binding, "reference")));
        });
        List<String> permissions = list(root.get("requestedPermissions"), "requestedPermissions").stream()
                .map(item -> {
                    if (!(item instanceof String text)) throw new IllegalArgumentException("requested permission must be a string");
                    return text;
                }).toList();
        return new InstalledAgent(string(root, "tenantId"), string(root, "projectId"), string(root, "installationId"),
                lock, bindings, permissions, Instant.parse(string(root, "installedAt")), Instant.parse(string(root, "updatedAt")));
    }

    @SuppressWarnings("unchecked")
    private static Map<String, Object> object(Object value, String name) {
        if (!(value instanceof Map<?, ?> map)) throw new IllegalArgumentException(name + " must be an object");
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, item) -> {
            if (!(key instanceof String text)) throw new IllegalArgumentException(name + " contains a non-string key");
            result.put(text, item);
        });
        return result;
    }

    private static List<?> list(Object value, String name) {
        if (!(value instanceof List<?> result)) throw new IllegalArgumentException(name + " must be an array");
        return result;
    }

    private static String string(Map<String, Object> value, String key) {
        Object item = value.get(key);
        if (!(item instanceof String result)) throw new IllegalArgumentException(key + " must be a string");
        return result;
    }

    private static IllegalStateException failure(String operation, Exception cause) {
        return new IllegalStateException("Installed agent store " + operation + " failed: " + cause.getMessage(), cause);
    }
}
