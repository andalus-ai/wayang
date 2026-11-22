package tech.kayys.wayang.rag;

import java.util.Set;

/** A bounded, tenant-scoped graph traversal request. No vendor query language is exposed. */
public record GraphQuery(
        String tenantId,
        String collectionId,
        Set<String> seedEntityIds,
        Direction direction,
        Set<String> predicates,
        int maxDepth,
        int maxNodes,
        int maxEdges) {

    public enum Direction { OUTGOING, INCOMING, BOTH }

    public GraphQuery {
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 200) {
            throw new RagException("graph query needs a tenantId of 1..200 characters");
        }
        if (collectionId == null || collectionId.isBlank() || collectionId.length() > 200) {
            throw new RagException("graph query needs a collectionId of 1..200 characters");
        }
        tenantId = tenantId.strip();
        collectionId = collectionId.strip();
        seedEntityIds = seedEntityIds == null ? Set.of() : Set.copyOf(seedEntityIds);
        if (seedEntityIds.isEmpty() || seedEntityIds.size() > 100
                || seedEntityIds.stream().anyMatch(id -> id == null || id.isBlank() || id.length() > 300)) {
            throw new RagException("graph query needs 1..100 valid seed entity ids");
        }
        direction = direction == null ? Direction.BOTH : direction;
        predicates = predicates == null ? Set.of() : predicates.stream()
                .filter(p -> p != null && !p.isBlank()).map(p -> p.strip().toUpperCase(java.util.Locale.ROOT))
                .collect(java.util.stream.Collectors.toUnmodifiableSet());
        if (maxDepth < 1 || maxDepth > 3) throw new RagException("graph traversal depth must be 1..3");
        if (maxNodes < 1 || maxNodes > 1000) throw new RagException("graph traversal node limit must be 1..1000");
        if (maxEdges < 1 || maxEdges > 2000) throw new RagException("graph traversal edge limit must be 1..2000");
    }
}
