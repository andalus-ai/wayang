package tech.kayys.wayang.rag;

import java.util.Map;
import java.util.Set;

/** Immutable, versioned allow-list for graph extraction. */
public record KnowledgeSchema(
        String schemaId,
        String version,
        Set<String> entityTypes,
        Map<String, RelationshipTypeDefinition> relationshipTypes) {

    public KnowledgeSchema {
        schemaId = required(schemaId, "schemaId", 200);
        version = required(version, "version", 100);
        entityTypes = entityTypes == null ? Set.of() : Set.copyOf(entityTypes);
        if (entityTypes.isEmpty() || entityTypes.stream().anyMatch(v -> v == null || v.isBlank())) {
            throw new RagException("knowledge schema must declare at least one entity type");
        }
        relationshipTypes = relationshipTypes == null ? Map.of() : Map.copyOf(relationshipTypes);
        for (Map.Entry<String, RelationshipTypeDefinition> entry : relationshipTypes.entrySet()) {
            if (entry.getKey() == null || entry.getValue() == null
                    || !entry.getKey().strip().equalsIgnoreCase(entry.getValue().predicate())) {
                throw new RagException("relationship schema key must match its predicate");
            }
            RelationshipTypeDefinition definition = entry.getValue();
            if (!entityTypes.containsAll(definition.fromEntityTypes())
                    || !entityTypes.containsAll(definition.toEntityTypes())) {
                throw new RagException("relationship endpoint types must be declared entity types");
            }
        }
    }

    public boolean allowsEntityType(String type) {
        return type != null && entityTypes.contains(type);
    }

    public RelationshipTypeDefinition relationship(String predicate) {
        return predicate == null ? null
                : relationshipTypes.get(predicate.strip().toUpperCase(java.util.Locale.ROOT));
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new RagException(field + " must contain 1.." + max + " characters");
        }
        return value.strip();
    }
}
