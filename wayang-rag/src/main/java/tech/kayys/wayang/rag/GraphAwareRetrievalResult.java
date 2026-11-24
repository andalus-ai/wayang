package tech.kayys.wayang.rag;

import java.util.List;

/** Fused document passages and graph provenance, retaining the underlying traversal result. */
public record GraphAwareRetrievalResult(List<Hit> hits, GraphTraversalResult traversal,
        int evidenceReferencesConsidered, boolean evidenceTruncated) {
    public GraphAwareRetrievalResult {
        hits = hits == null ? List.of() : List.copyOf(hits);
        traversal = traversal == null ? new GraphTraversalResult(List.of(), List.of(), false) : traversal;
    }
}
