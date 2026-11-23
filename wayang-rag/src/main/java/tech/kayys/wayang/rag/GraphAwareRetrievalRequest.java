package tech.kayys.wayang.rag;

import java.util.Set;

/** Bounded graph expansion request; seed IDs are resolved by the caller's query-understanding layer. */
public record GraphAwareRetrievalRequest(String tenantId, String collectionId, Set<String> seedEntityIds,
        GraphQuery.Direction direction, Set<String> predicates, int maxDepth, int maxNodes,
        int maxEdges, int maxEvidence, int topK) {
    public GraphAwareRetrievalRequest {
        if (tenantId == null || tenantId.isBlank() || tenantId.length() > 200
                || collectionId == null || collectionId.isBlank() || collectionId.length() > 200)
            throw new RagException("graph-aware retrieval requires tenant and collection scope");
        tenantId = tenantId.strip();
        collectionId = collectionId.strip();
        seedEntityIds = seedEntityIds == null ? Set.of() : Set.copyOf(seedEntityIds);
        if (seedEntityIds.size() > 100 || seedEntityIds.stream().anyMatch(id -> id == null || id.isBlank()))
            throw new RagException("graph-aware retrieval accepts at most 100 non-empty seed IDs");
        direction = direction == null ? GraphQuery.Direction.BOTH : direction;
        predicates = predicates == null ? Set.of() : Set.copyOf(predicates);
        if (maxDepth < 1 || maxDepth > 3 || maxNodes < 1 || maxNodes > 1000
                || maxEdges < 1 || maxEdges > 2000 || maxEvidence < 1 || maxEvidence > 2000
                || topK < 1 || topK > RetrievalConfig.MAX_TOP_K)
            throw new RagException("graph-aware retrieval limits are outside supported bounds");
    }
}
