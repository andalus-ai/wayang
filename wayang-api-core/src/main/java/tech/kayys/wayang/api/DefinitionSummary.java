package tech.kayys.wayang.api;

import java.time.Instant;

/** List-view row: what the Studio's project explorer shows. */
public record DefinitionSummary(DefinitionKind kind, String id, String name, boolean hasDraft, int latestVersion,
        Instant updatedAt, String updatedBy, String projectId) {
}
