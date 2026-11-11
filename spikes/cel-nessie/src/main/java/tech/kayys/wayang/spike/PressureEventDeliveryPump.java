package tech.kayys.wayang.spike;

import java.time.Clock;
import java.time.Duration;
import java.util.Objects;
import java.util.concurrent.atomic.LongAdder;

/**
 * Host-invoked, bounded delivery pass over a PressureEventStore.
 *
 * <p>This class deliberately owns no scheduler or background thread. The host
 * chooses cadence and retry backoff by invoking {@link #deliverOnce()}.</p>
 */
public final class PressureEventDeliveryPump {
    private final PressureEventStore store;
    private final PressureEventConsumer consumer;
    private final String consumerId;
    private final int batchSize;
    private final Duration leaseDuration;
    private final Clock clock;
    private final LongAdder claimedTotal = new LongAdder();
    private final LongAdder acknowledgedTotal = new LongAdder();
    private final LongAdder consumerFailureTotal = new LongAdder();
    private final LongAdder acknowledgementRejectedTotal = new LongAdder();
    private final LongAdder releaseRejectedTotal = new LongAdder();

    public PressureEventDeliveryPump(
            PressureEventStore store,
            PressureEventConsumer consumer,
            String consumerId,
            int batchSize,
            Duration leaseDuration,
            Clock clock) {
        this.store = Objects.requireNonNull(store, "store");
        this.consumer = Objects.requireNonNull(consumer, "consumer");
        this.consumerId = requireText(consumerId, "consumerId");
        if (batchSize < 1) throw new IllegalArgumentException("batchSize must be positive");
        this.batchSize = batchSize;
        this.leaseDuration = Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        this.clock = Objects.requireNonNull(clock, "clock");
    }

    /**
     * Claims and processes at most batchSize events. Store failures propagate
     * to the host; consumer RuntimeExceptions are isolated per event.
     */
    public BatchResult deliverOnce() {
        var claims = store.claim(consumerId, batchSize, clock.instant(), leaseDuration);
        claimedTotal.add(claims.size());
        int acknowledged = 0;
        int consumerFailures = 0;
        int acknowledgementRejected = 0;
        int releaseRejected = 0;

        for (var claim : claims) {
            var envelope = claim.envelope();
            try {
                consumer.deliver(envelope);
            } catch (RuntimeException deliveryFailure) {
                consumerFailures++;
                consumerFailureTotal.increment();
                if (!store.release(consumerId, envelope.eventId(), claim.claimToken())) {
                    releaseRejected++;
                    releaseRejectedTotal.increment();
                }
                continue;
            }

            if (store.acknowledge(consumerId, envelope.eventId(),
                    claim.claimToken(), clock.instant())) {
                acknowledged++;
                acknowledgedTotal.increment();
            } else {
                acknowledgementRejected++;
                acknowledgementRejectedTotal.increment();
            }
        }
        return new BatchResult(claims.size(), acknowledged, consumerFailures,
                acknowledgementRejected, releaseRejected);
    }

    public Snapshot snapshot() {
        return new Snapshot(claimedTotal.sum(), acknowledgedTotal.sum(),
                consumerFailureTotal.sum(), acknowledgementRejectedTotal.sum(),
                releaseRejectedTotal.sum());
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank() || !value.equals(value.trim())) {
            throw new IllegalArgumentException(name + " must be non-blank and trimmed");
        }
        return value;
    }

    public record BatchResult(
            int claimed,
            int acknowledged,
            int consumerFailures,
            int acknowledgementRejected,
            int releaseRejected) {
        public BatchResult {
            if (claimed < 0 || acknowledged < 0 || consumerFailures < 0
                    || acknowledgementRejected < 0 || releaseRejected < 0
                    || acknowledged + consumerFailures + acknowledgementRejected != claimed) {
                throw new IllegalArgumentException("invalid batch result counts");
            }
        }
    }

    /** Aggregate process-local counters; no event, stream, or consumer identifiers. */
    public record Snapshot(
            long claimed,
            long acknowledged,
            long consumerFailures,
            long acknowledgementRejected,
            long releaseRejected) {
        public Snapshot {
            if (claimed < 0 || acknowledged < 0 || consumerFailures < 0
                    || acknowledgementRejected < 0 || releaseRejected < 0) {
                throw new IllegalArgumentException("snapshot values must be non-negative");
            }
        }
    }
}
