package tech.kayys.wayang.spike;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

/** A leased event delivery; the claim token fences stale acknowledgements. */
public record ClaimedPressureEvent(
        PressureEventEnvelope envelope,
        String consumerId,
        UUID claimToken,
        Instant leaseExpiresAt,
        int deliveryAttempts) {
    public ClaimedPressureEvent {
        Objects.requireNonNull(envelope, "envelope");
        Objects.requireNonNull(consumerId, "consumerId");
        if (consumerId.isBlank() || !consumerId.equals(consumerId.trim())) {
            throw new IllegalArgumentException("consumerId must be non-blank and trimmed");
        }
        Objects.requireNonNull(claimToken, "claimToken");
        Objects.requireNonNull(leaseExpiresAt, "leaseExpiresAt");
        if (deliveryAttempts < 1) {
            throw new IllegalArgumentException("deliveryAttempts must be positive");
        }
    }
}
