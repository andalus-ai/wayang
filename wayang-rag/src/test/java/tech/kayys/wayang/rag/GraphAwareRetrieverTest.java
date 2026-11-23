package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.*;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphAwareRetrieverTest {
    private static KnowledgeEvidence evidence(String source, String version, String chunk) {
        return new KnowledgeEvidence(source, version, chunk, 0, 10, "extractor", 0.9);
    }
    private static GraphAwareRetrievalRequest request(Set<String> seeds) {
        return new GraphAwareRetrievalRequest("tenant-a", "docs", seeds, GraphQuery.Direction.BOTH,
                Set.of(), 2, 20, 20, 20, 5);
    }
    private static Hit hit(String doc, String chunk, String text) {
        return new Hit(new Chunk(chunk, doc, doc, 0, text, "", null, Map.of()), 1.0, text, "bm25");
    }

    @Test
    void fusesSourceBackedGraphPassagesWithDocumentHits() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeEvidence ev = evidence("source-a", "v1", "chunk-graph");
        store.upsertEntity(new KnowledgeEntity("tenant-a", "docs", "e1", "Policy",
                "Policy A", List.of(), Map.of(), List.of(ev)));
        GraphEvidenceLookup lookup = (tenant, collection, refs, limit) -> {
            assertEquals("tenant-a", tenant);
            assertEquals("docs", collection);
            assertEquals(1, refs.size());
            assertEquals("v1", refs.get(0).evidence().sourceVersion());
            return List.of(hit("source-a", "chunk-graph", "Graph-supported passage"));
        };
        GraphAwareRetrievalResult result = new GraphAwareRetriever(store, lookup).retrieve(
                List.of(hit("source-b", "chunk-base", "Base passage")), request(Set.of("e1")));
        assertEquals(2, result.hits().size());
        assertEquals(1, result.evidenceReferencesConsidered());
        assertEquals("hybrid+graph", result.hits().get(0).method());
        assertFalse(result.evidenceTruncated());
    }

    @Test
    void deduplicatesEvidenceReferencesAndPassages() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeEvidence ev = evidence("source-a", "v1", "chunk-1");
        store.upsertEntity(new KnowledgeEntity("tenant-a", "docs", "e1", "Policy",
                "Policy A", List.of(), Map.of(), List.of(ev, ev)));
        GraphAwareRetriever retriever = new GraphAwareRetriever(store,
                (tenant, collection, refs, limit) -> List.of(
                        hit("source-a", "chunk-1", "Same passage"), hit("source-a", "chunk-1", "Same passage")));
        GraphAwareRetrievalResult result = retriever.retrieve(List.of(), request(Set.of("e1")));
        assertEquals(1, result.evidenceReferencesConsidered());
        assertEquals(1, result.hits().size());
    }

    @Test
    void emptySeedsPreserveBaseRetrievalWithoutGraphCalls() {
        GraphAwareRetriever retriever = new GraphAwareRetriever(new InMemoryGraphStore(),
                (tenant, collection, refs, limit) -> { throw new AssertionError("must not be called"); });
        List<Hit> base = List.of(hit("source-a", "chunk-1", "Base"));
        GraphAwareRetrievalResult result = retriever.retrieve(base, request(Set.of()));
        assertEquals(base, result.hits());
        assertTrue(result.traversal().entities().isEmpty());
    }

    @Test
    void validatesLimitsAndDependencies() {
        assertThrows(RagException.class, () -> new GraphAwareRetriever(null, (a, b, c, d) -> List.of()));
        assertThrows(RagException.class, () -> new GraphAwareRetrievalRequest(
                "tenant-a", "docs", Set.of("e1"), GraphQuery.Direction.BOTH, Set.of(), 4, 10, 10, 10, 5));
    }
}
