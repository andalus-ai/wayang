package tech.kayys.wayang.rag;

import java.util.List;

/** Outcome of applying one validated chunk's candidates to a graph store. */
public record KnowledgeGraphIngestionResult(
        int entitiesUpserted,
        int relationshipsUpserted,
        int unresolvedEntities,
        int skippedRelationships,
        List<KnowledgeValidationIssue> issues) {
    public KnowledgeGraphIngestionResult {
        if (entitiesUpserted < 0 || relationshipsUpserted < 0
                || unresolvedEntities < 0 || skippedRelationships < 0) {
            throw new RagException("ingestion counts cannot be negative");
        }
        issues = issues == null ? List.of() : List.copyOf(issues);
    }
}
