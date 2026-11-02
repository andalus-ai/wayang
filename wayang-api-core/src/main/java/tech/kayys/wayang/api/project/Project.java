package tech.kayys.wayang.api.project;

import java.time.Instant;

/** A named workspace grouping definitions (a definition joins one via {@code studio.projectId}). */
public record Project(String tenantId, String id, String name, String description, String createdBy, Instant createdAt,
        Instant updatedAt) {
}
