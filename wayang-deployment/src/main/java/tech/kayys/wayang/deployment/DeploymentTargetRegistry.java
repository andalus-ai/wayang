package tech.kayys.wayang.deployment;

import java.util.Objects;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ConcurrentMap;

/** Explicit allow-list of trusted adapters; never loads classes from user-controlled names. */
public final class DeploymentTargetRegistry {
    private final ConcurrentMap<String, DeploymentTarget> targets = new ConcurrentHashMap<>();
    public void register(String targetId, DeploymentTarget target) {
        String id = DeploymentSnapshot.required(targetId, "targetId");
        Objects.requireNonNull(target, "target");
        if (targets.putIfAbsent(id, target) != null) throw new IllegalStateException("Deployment target is already registered: " + id);
    }
    public DeploymentTarget require(String targetId) {
        DeploymentTarget target = targets.get(DeploymentSnapshot.required(targetId, "targetId"));
        if (target == null) throw new IllegalArgumentException("No trusted deployment target registered: " + targetId);
        return target;
    }
    public boolean unregister(String targetId, DeploymentTarget expected) {
        return targets.remove(DeploymentSnapshot.required(targetId, "targetId"), Objects.requireNonNull(expected, "expected"));
    }
}
