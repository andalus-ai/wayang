package tech.kayys.wayang.rag;

import java.util.LinkedHashMap;
import java.util.Locale;
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
        Map<String, RelationshipTypeDefinition> normalized = new LinkedHashMap<>();
        Set<String> declaredEntityTypes = entityTypes;
        if (relationshipTypes != null) {
            for (Map.Entry<String, RelationshipTypeDefinition> entry : relationshipTypes.entrySet()) {
                String key = entry.getKey();
                RelationshipTypeDefinition definition = entry.getValue();
                if (key == null || definition == null
                        || !key.strip().equalsIgnoreCase(definition.predicate())) {
                    throw new RagException("relationship schema key must match its predicate");
                }
                String predicate = key.strip().toUpperCase(Locale.ROOT);
                if (normalized.putIfAbsent(predicate, definition) != null) {
                    throw new RagException("relationship schema contains duplicate normalized predicates");
                }
                if (!declaredEntityTypes.containsAll(definition.fromEntityTypes())
                        || !declaredEntityTypes.containsAll(definition.toEntityTypes())) {
                    throw new RagException("relationship endpoint types must be declared entity types");
                }
            }
        }
        relationshipTypes = Map.copyOf(normalized);
    }

    public boolean allowsEntityType(String type) {
        return type != null && entityTypes.contains(type);
    }

    public RelationshipTypeDefinition relationship(String predicate) {
        return predicate == null ? null : relationshipTypes.get(predicate.strip().toUpperCase(Locale.ROOT));
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new RagException(field + " must contain 1.." + max + " characters");
        }
        return value.strip();
    }
}
