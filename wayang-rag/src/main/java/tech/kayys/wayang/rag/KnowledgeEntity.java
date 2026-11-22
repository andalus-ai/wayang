package tech.kayys.wayang.rag;

import java.util.List;
import java.util.Map;

/**
 * A tenant- and collection-scoped canonical entity in the optional knowledge graph.
 *
 * <p>{@code id} is supplied by the graph ingestion/resolution layer and must be stable within
 * its scope. The core deliberately does not perform fuzzy identity matching or silently merge
 * entities. Properties are descriptive data, never executable instructions.
 */
public record KnowledgeEntity(
        String tenantId,
        String collectionId,
        String id,
        String type,
        String canonicalName,
        List<String> aliases,
        Map<String, String> properties,
        List<KnowledgeEvidence> evidence) {

    public KnowledgeEntity {
        tenantId = required(tenantId, "tenantId", 200);
        collectionId = required(collectionId, "collectionId", 200);
        id = required(id, "id", 300);
        type = required(type, "type", 120);
        canonicalName = required(canonicalName, "canonicalName", 500);
        aliases = aliases == null ? List.of() : aliases.stream()
                .filter(a -> a != null && !a.isBlank()).map(String::strip).distinct().toList();
        properties = properties == null ? Map.of() : Map.copyOf(properties);
        evidence = evidence == null ? List.of() : List.copyOf(evidence);
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new RagException(field + " must contain 1.." + max + " characters");
        }
        return value.strip();
    }
}
