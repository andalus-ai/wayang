package tech.kayys.wayang.control.dto;

/**
 * Connection between nodes in a route design.
 */
public record DesignConnection(
        String connectionId,
        String sourceId,
        String targetId,
        String sourcePort,
        String targetPort,
        String connectionType) {
}
