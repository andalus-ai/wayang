package tech.kayys.wayang.control.dto;

import java.time.Instant;
import java.util.List;
import java.util.Map;

/**
 * Node element in a route design.
 */
public record DesignNode(
        String nodeId,
        String nodeType,
        String label,
        DesignerPosition position,
        Map<String, Object> configuration,
        List<String> inputPorts,
        List<String> outputPorts,
        Instant createdAt) {
}
