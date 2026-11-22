package tech.kayys.wayang.rag;

import java.util.Optional;

/**
 * Vendor-neutral persistence port for optional graph knowledge.
 *
 * <p>Implementations must enforce tenant and collection isolation on every operation. The
 * reference in-memory implementation is bounded and process-local; production adapters can
 * implement this contract without exposing Cypher, Gremlin, SQL, or vendor types to workflows.
 */
public interface GraphStore {

    void upsertEntity(KnowledgeEntity entity);

    /** Both endpoint entities must already exist in the same tenant and collection. */
    void upsertRelationship(KnowledgeRelationship relationship);

    Optional<KnowledgeEntity> findEntity(String tenantId, String collectionId, String entityId);

    /** Performs a breadth-first traversal bounded by the limits in {@link GraphQuery}. */
    GraphTraversalResult traverse(GraphQuery query);
}
