package tech.kayys.wayang.deployment.quarkus;

import java.util.Objects;

import org.jboss.logging.Logger;

import io.quarkus.scheduler.Scheduled;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import tech.kayys.wayang.deployment.DeploymentLifecycleReconciliationWorker;
import tech.kayys.wayang.deployment.DeploymentLifecycleReconciliationWorkerResult;

/**
 * Thin Quarkus boundary around the scheduler-neutral reconciliation worker.
 *
 * The application must provide the worker as a CDI bean, including its JDBC stores and
 * authenticated-encryption context codec. This adapter owns only scheduling and batch bounds.
 */
@ApplicationScoped
public class DeploymentLifecycleReconciliationScheduledTask {
    private static final Logger LOG = Logger.getLogger(DeploymentLifecycleReconciliationScheduledTask.class);

    private final Instance<DeploymentLifecycleReconciliationWorker> worker;
    private final boolean enabled;
    private final int batchSize;

    public DeploymentLifecycleReconciliationScheduledTask(
            Instance<DeploymentLifecycleReconciliationWorker> worker,
            @ConfigProperty(name = "wayang.deployment.reconciliation.enabled", defaultValue = "false")
            boolean enabled,
            @ConfigProperty(name = "wayang.deployment.reconciliation.batch-size", defaultValue = "100")
            int batchSize) {
        this.worker = Objects.requireNonNull(worker, "worker");
        if (batchSize < 1 || batchSize > 1000) {
            throw new IllegalArgumentException("wayang.deployment.reconciliation.batch-size must be between 1 and 1000");
        }
        this.enabled = enabled;
        this.batchSize = batchSize;
    }

    /**
     * Quarkus skips overlapping invocations in this process. JDBC work leases provide
     * cross-process exclusion when multiple application instances share the same database.
     */
    @Scheduled(
            identity = "wayang-deployment-lifecycle-reconciliation",
            every = "${wayang.deployment.reconciliation.interval:30s}",
            concurrentExecution = Scheduled.ConcurrentExecution.SKIP)
    void runScheduledTick() {
        if (!enabled) return;
        if (!worker.isResolvable()) {
            LOG.error("Deployment reconciliation is enabled but no worker bean is available");
            return;
        }

        try {
            DeploymentLifecycleReconciliationWorkerResult result = worker.get().runOnce(batchSize);
            if (result.scanned() > 0) {
                LOG.infof("Deployment lifecycle reconciliation tick: scanned=%d claimed=%d reconciled=%d waiting=%d unresolved=%d conflicts=%d skipped=%d",
                        result.scanned(), result.claimed(), result.reconciled(), result.waiting(),
                        result.unresolved(), result.conflicts(), result.skipped());
            }
        } catch (RuntimeException failure) {
            // Do not log exception messages: JDBC and adapter exceptions can contain sensitive details.
            LOG.error("Deployment lifecycle reconciliation tick failed; durable work remains eligible for a later tick");
        }
    }
}