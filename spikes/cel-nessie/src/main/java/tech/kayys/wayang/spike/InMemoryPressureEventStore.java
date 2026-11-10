package tech.kayys.wayang.spike;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;

/**
 * Small synchronized reference implementation for contract tests and local
 * experiments only. It is not durable and must not be used as a production store.
 */
public final class InMemoryPressureEventStore implements PressureEventStore {
    private final Map<UUID, Entry> entries = new LinkedHashMap<>();
    private final Map<StreamSequence, UUID> streamSequences = new LinkedHashMap<>();

    @Override
    public synchronized PressureEventAppendResult append(PressureEventEnvelope envelope) {
        Objects.requireNonNull(envelope, "envelope");
        var existing = entries.get(envelope.eventId());
        if (existing != null) {
            if (existing.envelope.equals(envelope)) {
                return PressureEventAppendResult.DUPLICATE;
            }
            throw new IllegalStateException("eventId already exists with different content");
        }
        var key = new StreamSequence(envelope.streamId(), envelope.sequence());
        var existingId = streamSequences.get(key);
        if (existingId != null) {
            throw new IllegalStateException("stream sequence already exists for another event");
        }
        entries.put(envelope.eventId(), new Entry(envelope));
        streamSequences.put(key, envelope.eventId());
        return PressureEventAppendResult.APPENDED;
    }

    @Override
    public synchronized List<ClaimedPressureEvent> claim(
            String consumerId, int maxEvents, Instant now, Duration leaseDuration) {
        requireConsumer(consumerId);
        Objects.requireNonNull(now, "now");
        Objects.requireNonNull(leaseDuration, "leaseDuration");
        if (maxEvents < 1) throw new IllegalArgumentException("maxEvents must be positive");
        if (leaseDuration.isZero() || leaseDuration.isNegative()) {
            throw new IllegalArgumentException("leaseDuration must be positive");
        }
        var candidates = entries.values().stream()
                .filter(entry -> entry.acknowledgedAt == null)
                .filter(entry -> entry.claim == null || !now.isBefore(entry.claim.leaseExpiresAt()))
                .sorted(Comparator.comparing((Entry e) -> e.envelope.streamId())
                        .thenComparingLong(e -> e.envelope.sequence()))
                .limit(maxEvents)
                .toList();
        var result = new ArrayList<ClaimedPressureEvent>(candidates.size());
        for (var entry : candidates) {
            var claim = new ClaimedPressureEvent(entry.envelope, consumerId, UUID.randomUUID(),
                    now.plus(leaseDuration), ++entry.deliveryAttempts);
            entry.claim = claim;
            result.add(claim);
        }
        return List.copyOf(result);
    }

    @Override
    public synchronized boolean acknowledge(
            String consumerId, UUID eventId, UUID claimToken, Instant now) {
        requireConsumer(consumerId);
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(now, "now");
        var entry = entries.get(eventId);
        if (entry == null || entry.acknowledgedAt != null || entry.claim == null) return false;
        var claim = entry.claim;
        if (!claim.consumerId().equals(consumerId) || !claim.claimToken().equals(claimToken)
                || !now.isBefore(claim.leaseExpiresAt())) return false;
        entry.acknowledgedAt = now;
        entry.claim = null;
        return true;
    }

    @Override
    public synchronized boolean release(String consumerId, UUID eventId, UUID claimToken) {
        requireConsumer(consumerId);
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(claimToken, "claimToken");
        var entry = entries.get(eventId);
        if (entry == null || entry.acknowledgedAt != null || entry.claim == null) return false;
        if (!entry.claim.consumerId().equals(consumerId)
                || !entry.claim.claimToken().equals(claimToken)) return false;
        entry.claim = null;
        return true;
    }

    public synchronized int pendingCount() {
        return (int) entries.values().stream().filter(e -> e.acknowledgedAt == null).count();
    }

    public synchronized int acknowledgedCount() {
        return (int) entries.values().stream().filter(e -> e.acknowledgedAt != null).count();
    }

    private static void requireConsumer(String consumerId) {
        Objects.requireNonNull(consumerId, "consumerId");
        if (consumerId.isBlank() || !consumerId.equals(consumerId.trim())) {
            throw new IllegalArgumentException("consumerId must be non-blank and trimmed");
        }
    }

    private record StreamSequence(String streamId, long sequence) { }

    private static final class Entry {
        private final PressureEventEnvelope envelope;
        private ClaimedPressureEvent claim;
        private Instant acknowledgedAt;
        private int deliveryAttempts;

        private Entry(PressureEventEnvelope envelope) {
            this.envelope = envelope;
        }
    }
}
