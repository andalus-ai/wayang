package tech.kayys.wayang.deployment;

import java.util.List;
import java.util.Optional;

/**
 * Persistence boundary. Implementations must atomically append the event and advance the snapshot.
 * Durable adapters must enforce unique (deploymentId, idempotencyKey) and optimistic version checks.
 */
public interface DeploymentLedger {
    DeploymentSnapshot register(DeploymentSnapshot initial, DeploymentEvent installedEvent);
    Optional<DeploymentSnapshot> find(String deploymentId);
    List<DeploymentEvent> events(String deploymentId);
    DeploymentSnapshot append(long expectedVersion, DeploymentSnapshot next, DeploymentEvent event);
}
