package tech.kayys.wayang.spike;

import java.time.Instant;

/**
 * Optional bounded-retention capability for durable pressure-event stores.
 *
 * <p>Only acknowledged events older than the supplied cutoff may be removed.
 * Implementations must cap each call at {@code maxEvents}; scheduling and
 * retention policy remain host responsibilities.</p>
 */
public interface PressureEventRetention {
    int purgeAcknowledgedBefore(Instant cutoff, int maxEvents);
}
