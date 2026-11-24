package tech.kayys.wayang.agent;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Produces a non-mutating upgrade preview and retains the prior exact lock as rollback metadata. */
public final class AgentUpgradePlanner {

    public UpgradePlan preview(InstalledAgent current, InstalledAgent target) {
        Objects.requireNonNull(current, "current");
        Objects.requireNonNull(target, "target");
        if (!current.tenantId().equals(target.tenantId())
                || !current.projectId().equals(target.projectId())
                || !current.installationId().equals(target.installationId())) {
            throw new IllegalArgumentException("An upgrade must target the same tenant, project and installation");
        }
        if (!current.lock().packageId().equals(target.lock().packageId())) {
            throw new IllegalArgumentException("An installation cannot be upgraded to a different package identity");
        }
        Map<String, AgentPackageLock.LockedDependency> before = index(current.lock().dependencies());
        Map<String, AgentPackageLock.LockedDependency> after = index(target.lock().dependencies());
        List<DependencyChange> changes = new ArrayList<>();
        after.forEach((key, next) -> {
            AgentPackageLock.LockedDependency previous = before.get(key);
            if (previous == null) changes.add(new DependencyChange(key, Change.ADDED, null, next.version()));
            else if (!previous.version().equals(next.version()) || !previous.artifactSha256().equals(next.artifactSha256())) {
                changes.add(new DependencyChange(key, Change.UPGRADED, previous.version(), next.version()));
            }
        });
        before.forEach((key, previous) -> {
            if (!after.containsKey(key)) changes.add(new DependencyChange(key, Change.REMOVED, previous.version(), null));
        });
        changes.sort(java.util.Comparator.comparing(DependencyChange::dependency));
        return new UpgradePlan(current.lock(), target.lock(), !current.lock().version().equals(target.lock().version()),
                changes, current.lock());
    }

    private static Map<String, AgentPackageLock.LockedDependency> index(List<AgentPackageLock.LockedDependency> dependencies) {
        Map<String, AgentPackageLock.LockedDependency> result = new HashMap<>();
        dependencies.forEach(dependency -> result.put(dependency.kind() + ":" + dependency.packageId(), dependency));
        return result;
    }

    public enum Change { ADDED, UPGRADED, REMOVED }

    public record DependencyChange(String dependency, Change change, String fromVersion, String toVersion) { }

    public record UpgradePlan(AgentPackageLock currentLock, AgentPackageLock targetLock,
            boolean packageVersionChanged, List<DependencyChange> dependencyChanges,
            AgentPackageLock rollbackLock) {
        public UpgradePlan {
            Objects.requireNonNull(currentLock, "currentLock");
            Objects.requireNonNull(targetLock, "targetLock");
            Objects.requireNonNull(rollbackLock, "rollbackLock");
            dependencyChanges = List.copyOf(dependencyChanges);
        }
    }
}
