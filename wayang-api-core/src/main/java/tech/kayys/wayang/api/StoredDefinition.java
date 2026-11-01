package tech.kayys.wayang.api;

import java.time.Instant;
import java.util.Map;

/**
 * A definition document. {@code version == 0} is the editable draft (with a
 * {@code revision} counter for optimistic concurrency); {@code version >= 1}
 * are immutable published versions.
 */
public record StoredDefinition(String tenantId, DefinitionKind kind, String id, int version, long revision,
        Map<String, Object> document, Instant updatedAt, String updatedBy) {
    public StoredDefinition {
        document = java.util.Collections.unmodifiableMap(document);
    }

    public boolean isDraft() {
        return version == 0;
    }
}
