package tech.kayys.wayang.deployment;

import java.time.Clock;
import java.util.Objects;

/**
 * Engine- and transport-neutral lifecycle coordinator. It records decisions; it does not execute
 * processes, containers, migrations, or health probes. Those are explicit adapter responsibilities.
 */
public final class DeploymentLifecycleService {
    private final DeploymentLedger ledger;
    private final Clock clock;

    public DeploymentLifecycleService(DeploymentLedger ledger, Clock clock) {
        this.ledger = Objects.requireNonNull(ledger, "ledger");
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    public DeploymentSnapshot registerInstalled(String deploymentId, String tenantId, String environment,
            String targetId, String releaseId, String actor, String reason, String correlationId, String idempotencyKey) {
        var now = clock.instant();
        var initial = new DeploymentSnapshot(deploymentId, tenantId, environment, targetId, releaseId,
                DeploymentState.INSTALLED, 0, now, reason);
        var event = new DeploymentEvent(deploymentId, 0, null, DeploymentState.INSTALLED,
                actor, reason, correlationId, idempotencyKey, now);
        return ledger.register(initial, event);
    }

    public DeploymentSnapshot beginActivation(String id, long version, String actor, String reason, String correlation, String key) {
        return transition(id, version, DeploymentState.ACTIVATING, actor, reason, correlation, key);
    }
    public DeploymentSnapshot recordHealth(String id, long version, HealthOutcome outcome,
            String actor, String reason, String correlation, String key) {
        Objects.requireNonNull(outcome, "outcome");
        DeploymentState next = switch (outcome) {
            case HEALTHY -> DeploymentState.ACTIVE;
            case DEGRADED -> DeploymentState.DEGRADED;
            case UNHEALTHY, UNKNOWN -> DeploymentState.FAILED;
        };
        return transition(id, version, next, actor, reason, correlation, key);
    }
    public DeploymentSnapshot beginRollback(String id, long version, String actor, String reason, String correlation, String key) {
        return transition(id, version, DeploymentState.ROLLING_BACK, actor, reason, correlation, key);
    }
    public DeploymentSnapshot completeRollback(String id, long version, String actor, String reason, String correlation, String key) {
        return transition(id, version, DeploymentState.ROLLED_BACK, actor, reason, correlation, key);
    }
    public DeploymentSnapshot failRollback(String id, long version, String actor, String reason, String correlation, String key) {
        return transition(id, version, DeploymentState.FAILED, actor, reason, correlation, key);
    }
    public DeploymentSnapshot beginDeactivation(String id, long version, String actor, String reason, String correlation, String key) {
        return transition(id, version, DeploymentState.DEACTIVATING, actor, reason, correlation, key);
    }
    public DeploymentSnapshot completeDeactivation(String id, long version, String actor, String reason, String correlation, String key) {
        return transition(id, version, DeploymentState.DEACTIVATED, actor, reason, correlation, key);
    }
    public DeploymentSnapshot failDeactivation(String id, long version, String actor, String reason, String correlation, String key) {
        return transition(id, version, DeploymentState.FAILED, actor, reason, correlation, key);
    }

    private DeploymentSnapshot transition(String id, long expectedVersion, DeploymentState next,
            String actor, String reason, String correlationId, String idempotencyKey) {
        var current = ledger.find(id).orElseThrow(() -> new IllegalArgumentException("Unknown deployment: " + id));
        if (current.version() != expectedVersion) throw new IllegalStateException("Stale deployment version");
        if (!allowed(current.state(), next)) throw new IllegalStateException("Illegal deployment transition: " + current.state() + " -> " + next);
        var now = clock.instant();
        var updated = current.advance(next, now, reason);
        var event = new DeploymentEvent(id, updated.version(), current.state(), next,
                actor, reason, correlationId, idempotencyKey, now);
        return ledger.append(expectedVersion, updated, event);
    }

    static boolean allowed(DeploymentState from, DeploymentState to) {
        return switch (from) {
            case INSTALLED -> to == DeploymentState.ACTIVATING;
            case ACTIVATING -> to == DeploymentState.ACTIVE || to == DeploymentState.DEGRADED || to == DeploymentState.FAILED;
            case ACTIVE, DEGRADED -> to == DeploymentState.ROLLING_BACK || to == DeploymentState.DEACTIVATING;
            case FAILED -> to == DeploymentState.ROLLING_BACK;
            case ROLLING_BACK -> to == DeploymentState.ROLLED_BACK || to == DeploymentState.FAILED;
            case DEACTIVATING -> to == DeploymentState.DEACTIVATED || to == DeploymentState.FAILED;
            case ROLLED_BACK, DEACTIVATED -> false;
        };
    }
}
