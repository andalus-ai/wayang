package tech.kayys.wayang.control.dto;

import java.time.Instant;
import java.util.Map;
import java.util.UUID;

/**
 * Data Transfer Object for Project information.
 */
public record ProjectDTO(
        UUID projectId,
        String tenantId,
        String projectName,
        String description,
        ProjectType projectType,
        Instant createdAt,
        Instant updatedAt,
        String createdBy,
        boolean isActive,
        Map<String, Object> metadata) {
}
