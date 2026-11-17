package tech.kayys.wayang.deployment.quarkus;

import java.time.Duration;
import java.util.EnumMap;
import java.util.Map;
import java.util.concurrent.atomic.AtomicLong;

import org.jboss.logging.Logger;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import tech.kayys.wayang.deployment.DeploymentLifecycleReconciliationWorkStatus;
import tech.kayys.wayang.deployment.DeploymentLifecycleReconciliationWorkerResult;

/** Low-cardinality Micrometer instrumentation for reconciliation processing. */
@ApplicationScoped
public class DeploymentLifecycleReconciliationMetrics {
    private static final Logger LOG = Logger.getLogger(DeploymentLifecycleReconciliationMetrics.class);
    private static final String PREFIX = "wayang.deployment.reconciliation.";

    private final Counter ticks;
    private final Counter failures;
    private final Counter scanned;
    private final Counter claimed;
    private final Counter reconciled;
    private final Counter waiting;
    private final Counter unresolved;
    private final Counter conflicts;
    private final Counter skipped;
    private final Timer duration;
    private final Instance<DeploymentLifecycleReconciliationQueueSnapshotProvider> snapshots;
    private final Map<DeploymentLifecycleReconciliationWorkStatus, AtomicLong> queueSizes =
            new EnumMap<>(DeploymentLifecycleReconciliationWorkStatus.class);

    @Inject
    public DeploymentLifecycleReconciliationMetrics(MeterRegistry registry,
            Instance<DeploymentLifecycleReconciliationQueueSnapshotProvider> snapshots) {
        this.snapshots = snapshots;
        this.ticks = registry.counter(PREFIX + "ticks");
        this.failures = registry.counter(PREFIX + "tick.failures");
        this.scanned = registry.counter(PREFIX + "receipts.scanned");
        this.claimed = registry.counter(PREFIX + "receipts.claimed");
        this.reconciled = registry.counter(PREFIX + "receipts.reconciled");
        this.waiting = registry.counter(PREFIX + "receipts.waiting");
        this.unresolved = registry.counter(PREFIX + "receipts.unresolved");
        this.conflicts = registry.counter(PREFIX + "receipts.conflicts");
        this.skipped = registry.counter(PREFIX + "receipts.skipped");
        this.duration = registry.timer(PREFIX + "tick.duration");

        for (DeploymentLifecycleReconciliationWorkStatus status : DeploymentLifecycleReconciliationWorkStatus.values()) {
            AtomicLong value = new AtomicLong(-1L);
            queueSizes.put(status, value);
            Gauge.builder(PREFIX + "queue", value, AtomicLong::doubleValue)
                    .tag("status", status.name().toLowerCase(java.util.Locale.ROOT))
                    .description("Durable reconciliation work count; -1 means no snapshot provider is configured")
                    .register(registry);
        }
    }

    public void record(DeploymentLifecycleReconciliationWorkerResult result, long elapsedNanos) {
        ticks.increment();
        scanned.increment(result.scanned());
        claimed.increment(result.claimed());
        reconciled.increment(result.reconciled());
        waiting.increment(result.waiting());
        unresolved.increment(result.unresolved());
        conflicts.increment(result.conflicts());
        skipped.increment(result.skipped());
        duration.record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
    }

    public void recordFailure(long elapsedNanos) {
        ticks.increment();
        failures.increment();
        duration.record(Duration.ofNanos(Math.max(0L, elapsedNanos)));
    }

    /** Refreshes optional aggregate queue gauges after a worker tick, never on scrape. */
    public void refreshQueueSnapshot() {
        if (!snapshots.isResolvable()) return;
        try {
            Map<DeploymentLifecycleReconciliationWorkStatus, Long> snapshot = snapshots.get().snapshot();
            if (snapshot == null) throw new IllegalStateException("Queue snapshot provider returned null");
            for (DeploymentLifecycleReconciliationWorkStatus status : DeploymentLifecycleReconciliationWorkStatus.values()) {
                Long count = snapshot.getOrDefault(status, 0L);
                if (count == null || count < 0) {
                    throw new IllegalArgumentException("Queue snapshot counts must be non-negative");
                }
            }
            for (DeploymentLifecycleReconciliationWorkStatus status : DeploymentLifecycleReconciliationWorkStatus.values()) {
                queueSizes.get(status).set(snapshot.getOrDefault(status, 0L));
            }
        } catch (RuntimeException unavailable) {
            // Keep the last successful snapshot; never let telemetry break reconciliation.
            LOG.warn("Unable to refresh deployment reconciliation queue metrics");
        }
    }
}
