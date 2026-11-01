package tech.kayys.wayang.runtime;

import java.time.Instant;
import java.util.Map;

/**
 * A pending external completion (human task, timer, message).
 *
 * @param dueAt          timers: when to fire (null otherwise)
 * @param messageName    message waits: the awaited message name (null otherwise)
 * @param correlationKey message waits: key that selects the process instance (null otherwise)
 */
public record WaitTicket(String token, String nodeId, String kind, Map<String, Object> info, Instant dueAt,
        String messageName, String correlationKey) {
    public WaitTicket {
        info = info == null ? Map.of() : Map.copyOf(info);
    }

    public WaitTicket(String token, String nodeId, String kind, Map<String, Object> info) {
        this(token, nodeId, kind, info, null, null, null);
    }
}
