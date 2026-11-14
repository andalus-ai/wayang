package tech.kayys.wayang.spike;

import java.time.Instant;
import java.util.Objects;

/** Value-free, aggregate health view of an optional pressure-event store. */
public record PressureEventStoreHealth(
        boolean databaseAvailable,
        int schemaVersion,
        long pendingEvents,
        long expiredLeases,
        Instant oldestPendingAt,
        Instant observedAt,
        String failureCode) {

    public PressureEventStoreHealth {
        if (schemaVersion < 0 || pendingEvents < 0 || expiredLeases < 0) {
            throw new IllegalArgumentException("health counts and schema version must be non-negative");
        }
        Objects.requireNonNull(observedAt, "observedAt");
        if (databaseAvailable && failureCode != null) {
            throw new IllegalArgumentException("available store cannot have a failure code");
        }
        if (!databaseAvailable && (failureCode == null || failureCode.isBlank())) {
            throw new IllegalArgumentException("unavailable store requires a stable failure code");
        }
    }

    public static PressureEventStoreHealth unavailable(String failureCode, Instant observedAt) {
        return new PressureEventStoreHealth(false, 0, 0, 0, null, observedAt, failureCode);
    }
}
