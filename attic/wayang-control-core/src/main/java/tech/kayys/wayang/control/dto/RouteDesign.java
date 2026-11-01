package tech.kayys.wayang.control.dto;

import java.util.List;

/**
 * Design representation of an integration route.
 */
public record RouteDesign(
        String routeId,
        String name,
        String description,
        String category,
        String tenantId,
        List<DesignNode> nodes,
        List<DesignConnection> connections,
        DesignMetadata metadata) {
}
