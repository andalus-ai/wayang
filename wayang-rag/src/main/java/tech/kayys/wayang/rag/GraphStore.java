package tech.kayys.wayang.rag;

import java.util.List;
import java.util.Optional;

/** Vendor-neutral persistence port for optional graph knowledge. */
public interface GraphStore {
    /** Upserts an entity and merges its evidence idempotently. */
    void upsertEntity(KnowledgeEntity entity);

    /** Both endpoint entities must already exist in the same tenant and collection. */
    void upsertRelationship(KnowledgeRelationship relationship);

    /**
     * Atomically upserts a group of entities and relationships. Relationship endpoints may
     * refer to existing entities or entities included in this batch. Implementations must
     * validate the entire mutation before committing; on failure the store remains unchanged.
     * Persistent adapters should use one database transaction.
     */
    void upsertGraph(List<KnowledgeEntity> entities, List<KnowledgeRelationship> relationships);

    Optional<KnowledgeEntity> findEntity(String tenantId, String collectionId, String entityId);

    /** Removes only evidence matching this exact source and source version. */
    EvidenceRetractionResult retractEvidence(
            String tenantId, String collectionId, String sourceId, String sourceVersion);

    /** Performs a breadth-first traversal bounded by the limits in {@link GraphQuery}. */
    GraphTraversalResult traverse(GraphQuery query);
}
