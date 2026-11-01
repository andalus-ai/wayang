package tech.kayys.wayang.control.dto.realtime;

import java.util.Map;
import java.util.Set;

public record ControlPlaneRealtimeEvent(
        String type,
        String channel,
        String schemaId,
        Map<String, Object> payload,
        Map<String, Object> metadata,
        Set<String> workspaceIds) {

    public ControlPlaneRealtimeEvent {
        payload = payload == null ? Map.of() : payload;
        metadata = metadata == null ? Map.of() : metadata;
        workspaceIds = workspaceIds == null ? Set.of() : Set.copyOf(workspaceIds);
    }
}
