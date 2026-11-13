package tech.kayys.wayang.deployment;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

/** Thread-safe reference ledger for tests and single-process development only. */
public final class InMemoryDeploymentLedger implements DeploymentLedger {
    private final Map<String, DeploymentSnapshot> snapshots = new HashMap<>();
    private final Map<String, List<DeploymentEvent>> history = new HashMap<>();
    private final Map<String, Set<String>> idempotencyKeys = new HashMap<>();

    @Override
    public synchronized DeploymentSnapshot register(DeploymentSnapshot initial, DeploymentEvent event) {
        if (initial.state() != DeploymentState.INSTALLED || initial.version() != 0
                || event.fromState() != null || event.toState() != DeploymentState.INSTALLED
                || !initial.deploymentId().equals(event.deploymentId())) {
            throw new IllegalArgumentException("Initial deployment must be version-zero INSTALLED with a matching event");
        }
        if (snapshots.containsKey(initial.deploymentId())) throw new IllegalStateException("Deployment already exists");
        snapshots.put(initial.deploymentId(), initial);
        history.put(initial.deploymentId(), new ArrayList<>(List.of(event)));
        idempotencyKeys.put(initial.deploymentId(), new HashSet<>(Set.of(event.idempotencyKey())));
        return initial;
    }

    @Override public synchronized Optional<DeploymentSnapshot> find(String id) {
        return Optional.ofNullable(snapshots.get(id));
    }

    @Override public synchronized List<DeploymentEvent> events(String id) {
        return List.copyOf(history.getOrDefault(id, List.of()));
    }

    @Override
    public synchronized DeploymentSnapshot append(long expectedVersion, DeploymentSnapshot next, DeploymentEvent event) {
        DeploymentSnapshot current = snapshots.get(next.deploymentId());
        if (current == null) throw new IllegalStateException("Unknown deployment: " + next.deploymentId());
        if (current.version() != expectedVersion || next.version() != expectedVersion + 1
                || event.sequence() != next.version() || event.fromState() != current.state()
                || event.toState() != next.state() || !event.deploymentId().equals(next.deploymentId())) {
            throw new IllegalStateException("Deployment version or event does not match current state");
        }
        Set<String> keys = idempotencyKeys.get(next.deploymentId());
        if (keys.contains(event.idempotencyKey())) throw new IllegalStateException("Duplicate idempotency key");
        history.get(next.deploymentId()).add(event);
        keys.add(event.idempotencyKey());
        snapshots.put(next.deploymentId(), next);
        return next;
    }
}
