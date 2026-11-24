package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class QueryEntityLinkerTest {
    private static final RegexQueryMentionExtractor EXTRACTOR = new RegexQueryMentionExtractor(
            List.of(new RegexQueryMentionExtractor.Rule(
                    "Organization", Pattern.compile("(?i)(Acme Corp)"), 1, 0.95)), 20);

    private static KnowledgeEntity entity(String tenant, String id, String name, List<String> aliases) {
        return new KnowledgeEntity(tenant, "docs", id, "Organization", name, aliases, Map.of(),
                List.of(new KnowledgeEvidence("source-" + id, "v1", "chunk-" + id,
                        0, 12, "test", 0.9)));
    }

    private static GraphAwareRetrievalRequest request() {
        return new GraphAwareRetrievalRequest("tenant-a", "docs", Set.of(),
                GraphQuery.Direction.BOTH, Set.of(), 2, 20, 20, 20, 5);
    }

    private static Hit hit(String doc, String chunk, String text) {
        return new Hit(new Chunk(chunk, doc, doc, 0, text, "", null, Map.of()), 1.0, text, "bm25");
    }

    @Test
    void resolvesExactQueryMentionWithinTenantAndCollection() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertEntity(entity("tenant-a", "acme", "Acme Corporation", List.of("Acme Corp")));
        store.upsertEntity(entity("tenant-b", "other", "Acme Corporation", List.of("Acme Corp")));

        QueryEntityLinkingResult result = new QueryEntityLinker(
                EXTRACTOR, store, new ExactAliasEntityResolver())
                .link("tenant-a", "docs", "Show me Acme Corp policy");

        assertEquals(Set.of("acme"), result.seedEntityIds());
        assertEquals(1, result.mentions().size());
        assertEquals(EntityResolutionDecision.Status.MATCHED, result.decisions().get(0).status());
    }

    @Test
    void ambiguousMentionsDoNotBecomeGraphSeeds() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertEntity(entity("tenant-a", "acme-1", "Acme One", List.of("Acme Corp")));
        store.upsertEntity(entity("tenant-a", "acme-2", "Acme Two", List.of("Acme Corp")));

        QueryEntityLinkingResult result = new QueryEntityLinker(
                EXTRACTOR, store, new ExactAliasEntityResolver())
                .link("tenant-a", "docs", "What does Acme Corp own?");

        assertTrue(result.seedEntityIds().isEmpty());
        assertEquals(1, result.ambiguousCount());
        assertEquals(EntityResolutionDecision.Status.AMBIGUOUS, result.decisions().get(0).status());
    }

    @Test
    void noMentionOrNoMatchLeavesDocumentRetrievalAvailable() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        QueryEntityLinker linker = new QueryEntityLinker(EXTRACTOR, store, new ExactAliasEntityResolver());
        GraphAwareRetriever retriever = new GraphAwareRetriever(store,
                (tenant, collection, refs, limit) -> { throw new AssertionError("no seeds means no evidence lookup"); });
        Hit base = hit("doc", "chunk", "Document-only answer");
        QueryGraphRetrievalResult result = retriever.retrieveQuery(List.of(base),
                "Tell me about an unknown organization", linker, request());

        assertTrue(result.linking().seedEntityIds().isEmpty());
        assertEquals(List.of(base), result.retrieval().hits());
    }

    @Test
    void queryLinkingFeedsResolvedSeedsIntoGraphAwareRetrieval() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertEntity(entity("tenant-a", "acme", "Acme Corporation", List.of("Acme Corp")));
        GraphAwareRetriever retriever = new GraphAwareRetriever(store,
                (tenant, collection, refs, limit) -> List.of(hit("source-acme", "chunk-acme", "Acme source passage")));
        QueryEntityLinker linker = new QueryEntityLinker(EXTRACTOR, store, new ExactAliasEntityResolver());

        QueryGraphRetrievalResult result = retriever.retrieveQuery(List.of(),
                "Find Acme Corp", linker, request());

        assertEquals(Set.of("acme"), result.linking().seedEntityIds());
        assertEquals(1, result.retrieval().evidenceReferencesConsidered());
        assertEquals("hybrid+graph", result.retrieval().hits().get(0).method());
    }

    @Test
    void rejectsLookupResultsOutsideRequestedScope() {
        GraphEntityLookup leakingLookup = (tenant, collection, types, limit) ->
                List.of(entity("tenant-b", "leak", "Acme Corporation", List.of("Acme Corp")));
        QueryEntityLinker linker = new QueryEntityLinker(EXTRACTOR, leakingLookup, new ExactAliasEntityResolver());

        assertThrows(RagException.class, () -> linker.link("tenant-a", "docs", "Acme Corp"));
    }
}
