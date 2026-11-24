package tech.kayys.wayang.rag;

import java.util.List;

/** Explicit tenant/collection boundary and candidate set for canonical entity resolution. */
public record EntityResolutionRequest(
        String tenantId, String collectionId,
        List<EntityCandidate> candidates, List<KnowledgeEntity> existingEntities) {
    public EntityResolutionRequest {
        tenantId = required(tenantId, "tenantId");
        collectionId = required(collectionId, "collectionId");
        candidates = candidates == null ? List.of() : List.copyOf(candidates);
        existingEntities = existingEntities == null ? List.of() : List.copyOf(existingEntities);
    }
    private static String required(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 200) {
            throw new RagException(field + " must contain 1..200 characters");
        }
        return value.strip();
    }
}
