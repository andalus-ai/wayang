package tech.kayys.wayang.deployment.quarkus;

import io.quarkus.arc.Unremovable;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import tech.kayys.wayang.deployment.DeploymentLifecycleReconciliationWorker;

/**
 * Reports whether the optional reconciliation capability is wired.
 *
 * Backlog size, WAITING work, and UNRESOLVED receipts deliberately do not affect readiness.
 */
@Readiness
@ApplicationScoped
@Unremovable
public class DeploymentLifecycleReconciliationReadinessCheck implements HealthCheck {
    private final Instance<DeploymentLifecycleReconciliationWorker> worker;
    private final boolean enabled;

    public DeploymentLifecycleReconciliationReadinessCheck(
            Instance<DeploymentLifecycleReconciliationWorker> worker,
            @ConfigProperty(name = "wayang.deployment.reconciliation.enabled", defaultValue = "false")
            boolean enabled) {
        this.worker = worker;
        this.enabled = enabled;
    }

    @Override
    public HealthCheckResponse call() {
        boolean workerResolvable = worker.isResolvable();
        boolean ready = isReady(enabled, workerResolvable);
        return HealthCheckResponse.named("wayang-deployment-reconciliation")
                .status(ready)
                .withData("enabled", enabled)
                .withData("workerResolvable", workerResolvable)
                .build();
    }

    static boolean isReady(boolean enabled, boolean workerResolvable) {
        return !enabled || workerResolvable;
    }
}
