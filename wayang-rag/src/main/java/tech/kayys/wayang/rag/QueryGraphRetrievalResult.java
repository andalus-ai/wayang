package tech.kayys.wayang.rag;

/** Combined query-linking diagnostics and ordinary/graph-aware retrieval output. */
public record QueryGraphRetrievalResult(
        QueryEntityLinkingResult linking, GraphAwareRetrievalResult retrieval) {
    public QueryGraphRetrievalResult {
        linking = linking == null ? new QueryEntityLinkingResult(null, null, null) : linking;
        if (retrieval == null) throw new RagException("query graph retrieval result is required");
    }
}
