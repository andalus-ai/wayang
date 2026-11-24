package tech.kayys.wayang.rag;

import java.util.Set;

/** Allowed endpoint types for one directed relationship predicate. */
public record RelationshipTypeDefinition(
        String predicate,
        Set<String> fromEntityTypes,
        Set<String> toEntityTypes) {

    public RelationshipTypeDefinition {
        if (predicate == null || predicate.isBlank() || predicate.length() > 120) {
            throw new RagException("relationship predicate must contain 1..120 characters");
        }
        predicate = predicate.strip().toUpperCase(java.util.Locale.ROOT);
        fromEntityTypes = fromEntityTypes == null ? Set.of() : Set.copyOf(fromEntityTypes);
        toEntityTypes = toEntityTypes == null ? Set.of() : Set.copyOf(toEntityTypes);
        if (fromEntityTypes.isEmpty() || toEntityTypes.isEmpty()
                || fromEntityTypes.stream().anyMatch(v -> v == null || v.isBlank())
                || toEntityTypes.stream().anyMatch(v -> v == null || v.isBlank())) {
            throw new RagException("relationship endpoint type sets must be non-empty");
        }
    }
}
