package tech.kayys.wayang.rag;

import java.util.List;

/** Provider-neutral SPI that extracts directed relationships between known chunk-local candidates. */
@FunctionalInterface
public interface RelationshipExtractor {

    /**
     * Extract relationships referencing only the supplied entity candidate IDs.
     * Implementations must return chunk-local offsets and must not persist graph data.
     */
    List<RelationshipCandidate> extract(KnowledgeExtractionInput input, List<EntityCandidate> entities);
}
