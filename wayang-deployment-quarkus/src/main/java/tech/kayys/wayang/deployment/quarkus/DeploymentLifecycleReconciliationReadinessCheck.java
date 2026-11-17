package tech.kayys.wayang.deployment.quarkus;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.health.HealthCheck;
import org.eclipse.microprofile.health.HealthCheckResponse;
import org.eclipse.microprofile.health.Readiness;
import tech.kayys.wayang.deployment.DeploymentLifecycleReconciliationWorker;

/**
 * Readiness for the optional reconciliation integration.
 *
 * Unresolved or waiting work is deliberately not an application readiness failure:
 * those are durable operational outcomes requiring retry or operator attention.
 */
@Readiness
@ApplicationScoped
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
        if (!enabled) {
            return HealthCheckResponse.named("wayang-deployment-reconciliation")
                    .up().withData("enabled", false).build();
        }
        if (!worker.isResolvable()) {
            return HealthCheckResponse.named("wayang-deployment-reconciliation")
                    .down().withData("enabled", true).withData("reason", "worker-bean-unavailable").build();
        }
        return HealthCheckResponse.named("wayang-deployment-reconciliation")
                .up().withData("enabled", true).build();
    }
}
