package tech.kayys.wayang.rag;

import java.util.List;
import java.util.Map;

/**
 * A directed, typed relationship between two entities in the same tenant and collection.
 * Every extracted relationship should carry at least one evidence reference.
 */
public record KnowledgeRelationship(
        String tenantId,
        String collectionId,
        String id,
        String fromEntityId,
        String predicate,
        String toEntityId,
        Map<String, String> properties,
        List<KnowledgeEvidence> evidence,
        double confidence) {

    public KnowledgeRelationship {
        tenantId = required(tenantId, "tenantId", 200);
        collectionId = required(collectionId, "collectionId", 200);
        id = required(id, "id", 300);
        fromEntityId = required(fromEntityId, "fromEntityId", 300);
        predicate = required(predicate, "predicate", 120).toUpperCase(java.util.Locale.ROOT);
        toEntityId = required(toEntityId, "toEntityId", 300);
        if (fromEntityId.equals(toEntityId) && predicate.isBlank()) {
            throw new RagException("a self relationship needs a predicate");
        }
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new RagException("relationship confidence must be between 0 and 1");
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new RagException(field + " must contain 1.." + max + " characters");
        }
        return value.strip();
    }
}
