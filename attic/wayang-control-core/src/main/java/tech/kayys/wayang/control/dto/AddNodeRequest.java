package tech.kayys.wayang.control.dto;

import java.util.Map;

/**
 * Request to add a node to a route design.
 */
public record AddNodeRequest(
        String nodeType,
        String label,
        DesignerPosition position,
        Map<String, Object> configuration) {
}
