package tech.kayys.wayang.deployment.quarkus;

import java.util.concurrent.atomic.AtomicLong;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.wayang.deployment.DeploymentLifecycleReconciliationWorkerResult;

/** Low-cardinality counters for reconciliation worker outcomes. */
@ApplicationScoped
public class DeploymentLifecycleReconciliationMetrics {
    private final Counter scanned;
    private final Counter claimed;
    private final Counter reconciled;
    private final Counter waiting;
    private final Counter unresolved;
    private final Counter conflicts;
    private final Counter skipped;
    private final Counter failedTicks;
    private final AtomicLong lastSuccessfulTickEpochMillis = new AtomicLong();

    public DeploymentLifecycleReconciliationMetrics(MeterRegistry registry) {
        scanned = counter(registry, "scanned");
        claimed = counter(registry, "claimed");
        reconciled = counter(registry, "reconciled");
        waiting = counter(registry, "waiting");
        unresolved = counter(registry, "unresolved");
        conflicts = counter(registry, "conflicts");
        skipped = counter(registry, "skipped");
        failedTicks = counter(registry, "failed_ticks");
        registry.gauge("wayang.deployment.reconciliation.last.success.epoch.millis",
                lastSuccessfulTickEpochMillis);
    }

    public void record(DeploymentLifecycleReconciliationWorkerResult result) {
        scanned.increment(result.scanned());
        claimed.increment(result.claimed());
        reconciled.increment(result.reconciled());
        waiting.increment(result.waiting());
        unresolved.increment(result.unresolved());
        conflicts.increment(result.conflicts());
        skipped.increment(result.skipped());
        lastSuccessfulTickEpochMillis.set(System.currentTimeMillis());
    }

    public void recordFailure() {
        failedTicks.increment();
    }

    private static Counter counter(MeterRegistry registry, String outcome) {
        return Counter.builder("wayang.deployment.reconciliation." + outcome)
                .description("Cumulative deployment lifecycle reconciliation " + outcome)
                .register(registry);
    }
}
