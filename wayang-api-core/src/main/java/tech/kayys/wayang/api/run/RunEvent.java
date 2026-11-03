package tech.kayys.wayang.api.run;

import tech.kayys.wayang.api.definition.Studio;

import java.time.Instant;
import java.util.Map;

/** Something that happened to a run; streamed to the Studio / console over WebSocket and gRPC. */
public record RunEvent(Type type, String tenantId, String runId, String nodeId, Instant time, Map<String, Object> data) {
    public enum Type {
        RUN_STARTED, NODE_COMPLETED, TASK_CREATED, TASK_COMPLETED, RUN_COMPLETED, RUN_FAILED, RUN_CANCELLED,
        /** Synthetic: the subscriber was too slow and {@code data.dropped} events were discarded. */
        OVERFLOW
    }

    public RunEvent {
        data = data == null ? Map.of() : Map.copyOf(data);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("type", type.name());
        m.put("runId", runId);
        if (nodeId != null) {
            m.put("nodeId", nodeId);
        }
        m.put("time", time.toString());
        m.put("data", data);
        return m;
    }
}
