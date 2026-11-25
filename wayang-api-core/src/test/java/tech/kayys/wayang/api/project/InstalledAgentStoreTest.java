package tech.kayys.wayang.api.project;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.agent.AgentPackageLock;
import tech.kayys.wayang.agent.InstalledAgent;

class InstalledAgentStoreTest {

    private static InstalledAgent installed(String tenant, String project, String id) {
        var lock = AgentPackageLock.create("acme:support", "1.0.0", "a".repeat(64), List.of());
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        return new InstalledAgent(tenant, project, id, lock, Map.of(
                "api-token", new InstalledAgent.ConfigurationBinding("secret-reference", "secret://crm/token")),
                List.of("secret:crm"), now, now);
    }

    @Test
    void scopesInstallationLookupAndListingByTenantAndProject() {
        InstalledAgentStore store = new InstalledAgentStore.InMemory();
        store.save(installed("tenant-a", "project-1", "support"));
        store.save(installed("tenant-a", "project-2", "support"));
        store.save(installed("tenant-b", "project-1", "support"));

        assertEquals(1, store.list("tenant-a", "project-1").size());
        assertTrue(store.get("tenant-a", "project-1", "support").isPresent());
        assertTrue(store.get("tenant-b", "project-2", "support").isEmpty());
        assertTrue(store.delete("tenant-a", "project-1", "support"));
        assertTrue(store.get("tenant-a", "project-1", "support").isEmpty());
        assertTrue(store.get("tenant-a", "project-2", "support").isPresent());
    }

    @Test
    void ordersListingAndRejectsInstallationTimeRegression() {
        InstalledAgentStore store = new InstalledAgentStore.InMemory();
        store.save(installed("tenant-a", "project-1", "z-agent"));
        store.save(installed("tenant-a", "project-1", "a-agent"));
        assertEquals(List.of("a-agent", "z-agent"),
                store.list("tenant-a", "project-1").stream().map(InstalledAgent::installationId).toList());
        var older = new InstalledAgent("tenant-a", "project-1", "z-agent",
                AgentPackageLock.create("acme:support", "1.0.0", "a".repeat(64), List.of()),
                Map.of(), List.of(), Instant.parse("2025-01-01T00:00:00Z"),
                Instant.parse("2025-01-01T00:00:00Z"));
        assertThrows(IllegalArgumentException.class, () -> store.save(older));
    }
}
