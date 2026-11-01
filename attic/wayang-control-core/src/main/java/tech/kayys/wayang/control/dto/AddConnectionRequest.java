package tech.kayys.wayang.control.dto;

/**
 * Request to add a connection between nodes in a route design.
 */
public record AddConnectionRequest(
        String sourceId,
        String targetId,
        String sourcePort,
        String targetPort) {
}
