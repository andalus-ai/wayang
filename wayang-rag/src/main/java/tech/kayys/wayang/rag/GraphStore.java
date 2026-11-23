package tech.kayys.wayang.rag;

import java.util.Optional;

/**
 * Vendor-neutral persistence port for optional graph knowledge.
 *
 * <p>Implementations must enforce tenant and collection isolation on every operation.
 */
public interface GraphStore {

    /** Upserts an entity and merges its evidence idempotently. */
    void upsertEntity(KnowledgeEntity entity);

    /** Both endpoint entities must already exist in the same tenant and collection. */
    void upsertRelationship(KnowledgeRelationship relationship);

    Optional<KnowledgeEntity> findEntity(String tenantId, String collectionId, String entityId);

    /** Removes only evidence matching this exact source and source version. */
    EvidenceRetractionResult retractEvidence(
            String tenantId, String collectionId, String sourceId, String sourceVersion);

    /** Performs a breadth-first traversal bounded by the limits in {@link GraphQuery}. */
    GraphTraversalResult traverse(GraphQuery query);
}
