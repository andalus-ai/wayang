package tech.kayys.wayang.deployment.quarkus;

import java.util.Map;

import tech.kayys.wayang.deployment.DeploymentLifecycleReconciliationWorkStatus;

/**
 * Optional application adapter for queue-depth metrics.
 *
 * Implementations should return a cheap, bounded aggregate snapshot and must not include
 * deployment IDs, tenant IDs, operation keys, or other high-cardinality dimensions.
 */
@FunctionalInterface
public interface DeploymentLifecycleReconciliationQueueSnapshotProvider {
    Map<DeploymentLifecycleReconciliationWorkStatus, Long> snapshot();
}
