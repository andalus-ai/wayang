package tech.kayys.wayang.control.dto;

import java.util.Map;

/**
 * Metadata for a route design.
 */
public record DesignMetadata(
        String author,
        String version,
        Map<String, Object> attributes,
        long lastModified) {
}
