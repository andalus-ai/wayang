package tech.kayys.wayang.runtime;

import java.time.Clock;
import java.time.Instant;
import java.util.Map;

/**
 * Fires due timers and correlates messages. Stateless: the schedule lives in
 * the {@link WaitRegistry}, so any node can run {@link #fireDue} on a schedule
 * (e.g. every few seconds); with a durable registry, running it on several
 * nodes is safe as long as {@link WaitRegistry#take} is atomic.
 */
public final class TimerService {

    public enum Delivery {
        DELIVERED, BUFFERED
    }

    private final WaitRegistry waits;
    private final WaitCompleter completer;
    private final Clock clock;

    public TimerService(WaitRegistry waits, WaitCompleter completer, Clock clock) {
        this.waits = waits;
        this.completer = completer;
        this.clock = clock;
    }

    /** @return number of timers completed */
    public int fireDue() {
        return fireDue(clock.instant());
    }

    public int fireDue(Instant now) {
        int fired = 0;
        for (WaitRegistry.Registered r : waits.due(now)) {
            if (completer.complete(r.ticket().token(), Map.of("firedAt", now.toString(), "dueAt", r.ticket().dueAt().toString()))) {
                fired++;
            }
        }
        return fired;
    }

    /**
     * Delivers a message to the matching waiting node, or buffers it when
     * nobody waits yet (so a message that overtakes the process is not lost).
     */
    public Delivery deliver(String name, String correlationKey, Map<String, Object> payload) {
        return deliver(null, name, correlationKey, payload);
    }

    /** As {@link #deliver(String, String, Map)} for the runs of one tenant: other tenants' waits and buffered messages are never touched. */
    public Delivery deliver(String tenant, String name, String correlationKey, Map<String, Object> payload) {
        correlationKey = WaitRegistry.scoped(tenant, correlationKey);
        var waiting = waits.findMessage(name, correlationKey);
        if (waiting.isPresent() && completer.complete(waiting.get().ticket().token(), payload)) {
            return Delivery.DELIVERED;
        }
        waits.bufferMessage(name, correlationKey, payload);
        return Delivery.BUFFERED;
    }
}
