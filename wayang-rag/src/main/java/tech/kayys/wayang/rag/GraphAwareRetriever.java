package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Optional graph expansion over ordinary document retrieval.
 * Graph traversal contributes only source passages resolved through the ACL-aware evidence port.
 */
public final class GraphAwareRetriever {
    private static final int RRF_K = 60;
    private final GraphStore graphStore;
    private final GraphEvidenceLookup evidenceLookup;

    public GraphAwareRetriever(GraphStore graphStore, GraphEvidenceLookup evidenceLookup) {
        if (graphStore == null || evidenceLookup == null)
            throw new RagException("graph-aware retrieval requires a graph store and evidence lookup");
        this.graphStore = graphStore;
        this.evidenceLookup = evidenceLookup;
    }

    public GraphAwareRetrievalResult retrieve(List<Hit> documentHits, GraphAwareRetrievalRequest request) {
        if (request == null) throw new RagException("graph-aware retrieval request is required");
        List<Hit> base = documentHits == null ? List.of() : List.copyOf(documentHits);
        if (request.seedEntityIds().isEmpty())
            return new GraphAwareRetrievalResult(base.stream().limit(request.topK()).toList(),
                    new GraphTraversalResult(List.of(), List.of(), false), 0, false);

        GraphTraversalResult traversal = graphStore.traverse(new GraphQuery(request.tenantId(),
                request.collectionId(), request.seedEntityIds(), request.direction(), request.predicates(),
                request.maxDepth(), request.maxNodes(), request.maxEdges()));
        List<GraphEvidenceReference> refs = references(traversal, request, request.maxEvidence());
        List<Hit> graphHits = refs.isEmpty() ? List.of() : evidenceLookup.findAuthorizedPassages(
                request.tenantId(), request.collectionId(), refs, request.maxEvidence());
        if (graphHits == null) graphHits = List.of();
        int total = countEvidence(traversal);
        return new GraphAwareRetrievalResult(fuse(base, graphHits, request.topK()), traversal,
                refs.size(), total > refs.size() || traversal.truncated());
    }

    /** Links query mentions first, then runs the same bounded graph-aware retrieval path. */
    public QueryGraphRetrievalResult retrieveQuery(
            List<Hit> documentHits, String query, QueryEntityLinker linker,
            GraphAwareRetrievalRequest request) {
        if (linker == null || request == null)
            throw new RagException("query graph retrieval requires a linker and retrieval request");
        QueryEntityLinkingResult linking = linker.link(request.tenantId(), request.collectionId(), query);
        Set<String> seeds = new LinkedHashSet<>(request.seedEntityIds());
        seeds.addAll(linking.seedEntityIds());
        GraphAwareRetrievalRequest linkedRequest = new GraphAwareRetrievalRequest(
                request.tenantId(), request.collectionId(), seeds, request.direction(),
                request.predicates(), request.maxDepth(), request.maxNodes(), request.maxEdges(),
                request.maxEvidence(), request.topK());
        return new QueryGraphRetrievalResult(linking, retrieve(documentHits, linkedRequest));
    }

    private static List<GraphEvidenceReference> references(
            GraphTraversalResult traversal, GraphAwareRetrievalRequest request, int limit) {
        List<GraphEvidenceReference> result = new ArrayList<>();
        Set<String> seen = new LinkedHashSet<>();
        for (KnowledgeEntity entity : traversal.entities()) {
            for (KnowledgeEvidence ev : entity.evidence()) {
                if (seen.add(key(entity.id(), null, ev)))
                    result.add(new GraphEvidenceReference(request.tenantId(), request.collectionId(),
                            entity.id(), null, ev));
                if (result.size() >= limit) return List.copyOf(result);
            }
        }
        for (KnowledgeRelationship relationship : traversal.relationships()) {
            for (KnowledgeEvidence ev : relationship.evidence()) {
                if (seen.add(key(null, relationship.id(), ev)))
                    result.add(new GraphEvidenceReference(request.tenantId(), request.collectionId(),
                            null, relationship.id(), ev));
                if (result.size() >= limit) return List.copyOf(result);
            }
        }
        return List.copyOf(result);
    }

    private static String key(String entity, String relationship, KnowledgeEvidence ev) {
        return String.join("\u0000", entity == null ? "" : entity, relationship == null ? "" : relationship,
                ev.sourceId(), ev.sourceVersion(), ev.chunkId(), Integer.toString(ev.startOffset()),
                Integer.toString(ev.endOffset()));
    }

    private static int countEvidence(GraphTraversalResult t) {
        return t.entities().stream().mapToInt(e -> e.evidence().size()).sum()
                + t.relationships().stream().mapToInt(r -> r.evidence().size()).sum();
    }

    private static List<Hit> fuse(List<Hit> base, List<Hit> graph, int topK) {
        Map<String, Hit> hits = new LinkedHashMap<>();
        Map<String, Double> scores = new LinkedHashMap<>();
        addRanked(base, hits, scores);
        addRanked(graph, hits, scores);
        return scores.entrySet().stream()
                .sorted(Map.Entry.<String, Double>comparingByValue().reversed().thenComparing(Map.Entry::getKey))
                .limit(topK).map(e -> {
                    Hit h = hits.get(e.getKey());
                    return new Hit(h.chunk(), e.getValue(), h.text(), "hybrid+graph");
                }).toList();
    }

    private static void addRanked(List<Hit> ranked, Map<String, Hit> hits, Map<String, Double> scores) {
        for (int i = 0; i < ranked.size(); i++) {
            Hit hit = ranked.get(i);
            if (hit == null || hit.chunk() == null) continue;
            String key = hit.chunk().docId() + "\u0000" + hit.chunk().id();
            hits.putIfAbsent(key, hit);
            scores.merge(key, 1.0 / (RRF_K + i + 1), Double::sum);
        }
    }
}
