package tech.kayys.wayang.control.dto;

/**
 * Request to create a new integration route.
 */
public record CreateRouteRequest(
        String name,
        String description,
        String category,
        String tenantId) {
}
