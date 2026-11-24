package tech.kayys.wayang.store.jdbc;

import static org.junit.jupiter.api.Assertions.*;

import java.sql.Connection;
import java.sql.Statement;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.agent.AgentPackageLock;
import tech.kayys.wayang.agent.InstalledAgent;

/** PostgreSQL round-trip test; skipped unless WAYANG_TEST_JDBC_URL is configured. */
class JdbcInstalledAgentStoreIT {

    @Test
    void persistsProjectScopedInstallationAndExactLock() throws Exception {
        String url = System.getenv("WAYANG_TEST_JDBC_URL");
        if (url == null || url.isBlank()) return;
        var dataSource = new JdbcWaitRegistryIT.DriverManagerDataSource(url);
        JdbcSchema.apply(dataSource);
        try (Connection connection = dataSource.getConnection(); Statement statement = connection.createStatement()) {
            statement.execute("DELETE FROM wayang_installed_agent");
        }

        Instant installedAt = Instant.parse("2026-11-24T00:00:00Z");
        var dependency = new AgentPackageLock.LockedDependency("agent", "acme:search", "1.1.0", "b".repeat(64));
        var lock = AgentPackageLock.create("acme:support", "1.2.0", "a".repeat(64), List.of(dependency));
        var installed = new InstalledAgent("tenant-a", "project-a", "support", lock,
                Map.of("crm-token", new InstalledAgent.ConfigurationBinding("secret-reference", "secret://crm/token")),
                List.of("secret:crm"), installedAt, installedAt);

        var store = new JdbcInstalledAgentStore(dataSource);
        store.save(installed);

        var restored = store.get("tenant-a", "project-a", "support").orElseThrow();
        assertEquals(installed, restored);
        assertEquals(1, store.list("tenant-a", "project-a").size());
        assertTrue(store.get("tenant-b", "project-a", "support").isEmpty());
        assertTrue(store.get("tenant-a", "project-b", "support").isEmpty());

        var newerLock = AgentPackageLock.create("acme:support", "1.3.0", "c".repeat(64), List.of(dependency));
        var upgraded = new InstalledAgent("tenant-a", "project-a", "support", newerLock,
                installed.configurationBindings(), installed.requestedPermissions(), installedAt, installedAt.plusSeconds(60));
        store.save(upgraded);
        assertEquals("1.3.0", store.get("tenant-a", "project-a", "support").orElseThrow().lock().version());
        assertThrows(IllegalArgumentException.class, () -> store.save(installed));
    }
}
