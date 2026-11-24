package tech.kayys.wayang.agent;

import static org.junit.jupiter.api.Assertions.*;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class AgentUpgradePlannerTest {

    private static InstalledAgent installed(String version, List<AgentPackageLock.LockedDependency> dependencies) {
        Instant now = Instant.parse("2026-11-24T00:00:00Z");
        var lock = AgentPackageLock.create("acme:assistant", version, "a".repeat(64), dependencies);
        return new InstalledAgent("tenant", "project", "assistant", lock, Map.of(), List.of(), now, now);
    }

    @Test
    void previewsDependencyChangesAndRetainsExactRollbackLock() {
        var oldDependency = new AgentPackageLock.LockedDependency("agent", "acme:search", "1.0.0", "b".repeat(64));
        var nextDependency = new AgentPackageLock.LockedDependency("agent", "acme:search", "1.2.0", "c".repeat(64));
        var added = new AgentPackageLock.LockedDependency("agent", "acme:memory", "2.0.0", "d".repeat(64));
        var current = installed("1.0.0", List.of(oldDependency));
        var target = installed("1.1.0", List.of(nextDependency, added));

        var plan = new AgentUpgradePlanner().preview(current, target);

        assertTrue(plan.packageVersionChanged());
        assertEquals(current.lock(), plan.rollbackLock());
        assertEquals(2, plan.dependencyChanges().size());
        assertTrue(plan.dependencyChanges().stream().anyMatch(change -> change.change() == AgentUpgradePlanner.Change.UPGRADED));
        assertTrue(plan.dependencyChanges().stream().anyMatch(change -> change.change() == AgentUpgradePlanner.Change.ADDED));
    }

    @Test
    void rejectsUpgradeAcrossProjectOrPackageIdentity() {
        var current = installed("1.0.0", List.of());
        var otherProject = new InstalledAgent("tenant", "other", "assistant", current.lock(), Map.of(), List.of(),
                current.installedAt(), current.updatedAt());
        assertThrows(IllegalArgumentException.class, () -> new AgentUpgradePlanner().preview(current, otherProject));

        var otherPackage = new InstalledAgent("tenant", "project", "assistant",
                AgentPackageLock.create("acme:other", "1.1.0", "a".repeat(64), List.of()), Map.of(), List.of(),
                current.installedAt(), current.updatedAt());
        assertThrows(IllegalArgumentException.class, () -> new AgentUpgradePlanner().preview(current, otherPackage));
    }
}
