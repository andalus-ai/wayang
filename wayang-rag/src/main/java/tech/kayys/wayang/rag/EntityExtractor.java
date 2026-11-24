package tech.kayys.wayang.rag;

import java.util.List;

/** Provider-neutral SPI that extracts entity mentions from one normalized chunk. */
@FunctionalInterface
public interface EntityExtractor {

    /**
     * Extract entity candidates. Implementations must return chunk-local offsets, bounded output,
     * and must not perform network access or mutate a graph store.
     */
    List<EntityCandidate> extract(KnowledgeExtractionInput input);
}
