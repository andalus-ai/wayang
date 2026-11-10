package tech.kayys.wayang.spike;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Persistence SPI for durable pressure-event delivery.
 *
 * <p>Implementations must make append uniqueness and claim/ack/release
 * transitions atomic across processes sharing the same store. The interface
 * does not prescribe a database, broker, transaction manager, or schema.</p>
 */
public interface PressureEventStore {
    /**
     * Append once by eventId and by (streamId, sequence). Re-appending the exact
     * same envelope returns DUPLICATE; reusing either key for different content
     * must fail rather than silently overwrite.
     */
    PressureEventAppendResult append(PressureEventEnvelope envelope);

    /**
     * Claim up to maxEvents in stable stream/sequence order. Pending events and
     * expired leases are eligible. A claim gets a fresh fencing token and lease.
     */
    List<ClaimedPressureEvent> claim(
            String consumerId, int maxEvents, Instant now, Duration leaseDuration);

    /** Acknowledge only the currently valid, unexpired claim token. */
    boolean acknowledge(String consumerId, UUID eventId, UUID claimToken, Instant now);

    /** Return a currently owned claim to pending; stale tokens must be ignored. */
    boolean release(String consumerId, UUID eventId, UUID claimToken);
}
