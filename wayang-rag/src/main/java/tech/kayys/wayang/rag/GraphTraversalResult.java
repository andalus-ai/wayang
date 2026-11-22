package tech.kayys.wayang.rag;

import java.util.List;

/** Results of a bounded traversal; {@code truncated} signals that a configured result cap was hit. */
public record GraphTraversalResult(
        List<KnowledgeEntity> entities,
        List<KnowledgeRelationship> relationships,
        boolean truncated) {

    public GraphTraversalResult {
        entities = entities == null ? List.of() : List.copyOf(entities);
        relationships = relationships == null ? List.of() : List.copyOf(relationships);
    }
}
