package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class GraphStoreTest {

    private static KnowledgeEvidence evidence(String source, String chunk) {
        return new KnowledgeEvidence(source, "v1", chunk, 0, 12, "test-extractor", 0.95);
    }

    private static KnowledgeEntity entity(String tenant, String id, String name) {
        return new KnowledgeEntity(tenant, "policies", id, "Organization", name,
                List.of(), Map.of(), List.of(evidence("handbook", "chunk-1")));
    }

    private static KnowledgeRelationship relation(String tenant, String id, String from, String to, String predicate) {
        return new KnowledgeRelationship(tenant, "policies", id, from, predicate, to,
                Map.of(), List.of(evidence("handbook", "chunk-1")), 0.9);
    }

    @Test
    void traversesOnlyWithinRequestedTenantAndCollection() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertEntity(entity("tenant-a", "sales", "Sales"));
        store.upsertEntity(entity("tenant-a", "policy", "Policy 2025"));
        store.upsertEntity(entity("tenant-b", "sales", "Other tenant sales"));
        store.upsertEntity(entity("tenant-b", "policy", "Other tenant policy"));
        store.upsertRelationship(relation("tenant-a", "r1", "sales", "policy", "GOVERNED_BY"));
        store.upsertRelationship(relation("tenant-b", "r1", "sales", "policy", "GOVERNED_BY"));

        GraphTraversalResult result = store.traverse(new GraphQuery("tenant-a", "policies",
                Set.of("sales"), GraphQuery.Direction.OUTGOING, Set.of("GOVERNED_BY"), 1, 10, 10));

        assertEquals(2, result.entities().size());
        assertEquals(1, result.relationships().size());
        assertEquals("tenant-a", result.entities().get(1).tenantId());
        assertFalse(result.truncated());
        assertTrue(store.findEntity("tenant-a", "other-collection", "sales").isEmpty());
    }

    @Test
    void rejectsRelationshipWithMissingEndpoint() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertEntity(entity("tenant-a", "sales", "Sales"));
        assertThrows(RagException.class,
                () -> store.upsertRelationship(relation("tenant-a", "r1", "sales", "missing", "GOVERNED_BY")));
    }

    @Test
    void enforcesTraversalLimitsAndReportsTruncation() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertEntity(entity("tenant-a", "a", "A"));
        store.upsertEntity(entity("tenant-a", "b", "B"));
        store.upsertEntity(entity("tenant-a", "c", "C"));
        store.upsertRelationship(relation("tenant-a", "ab", "a", "b", "LINKS_TO"));
        store.upsertRelationship(relation("tenant-a", "ac", "a", "c", "LINKS_TO"));

        GraphTraversalResult result = store.traverse(new GraphQuery("tenant-a", "policies",
                Set.of("a"), GraphQuery.Direction.OUTGOING, Set.of(), 1, 2, 10));

        assertEquals(2, result.entities().size());
        assertEquals(1, result.relationships().size());
        assertTrue(result.truncated());
    }

    @Test
    void validatesTraversalBoundsAndEvidenceConfidence() {
        assertThrows(RagException.class, () -> new GraphQuery("tenant-a", "policies",
                Set.of("a"), GraphQuery.Direction.BOTH, Set.of(), 4, 10, 10));
        assertThrows(RagException.class,
                () -> new KnowledgeEvidence("source", "v1", "chunk", 0, 2, "extractor", 1.1));
    }

    @Test
    void graphStoreEnforcesConfiguredCapacity() {
        InMemoryGraphStore store = new InMemoryGraphStore(1, 1);
        store.upsertEntity(entity("tenant-a", "a", "A"));
        assertThrows(RagException.class, () -> store.upsertEntity(entity("tenant-a", "b", "B")));
    }


    @Test
    void batchUpsertAcceptsRelationshipsWhoseEndpointsAreInSameBatch() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertGraph(
                List.of(entity("tenant-a", "sales", "Sales"), entity("tenant-a", "policy", "Policy")),
                List.of(relation("tenant-a", "r1", "sales", "policy", "GOVERNED_BY")));
        GraphTraversalResult result = store.traverse(new GraphQuery("tenant-a", "policies",
                Set.of("sales"), GraphQuery.Direction.OUTGOING, Set.of(), 1, 10, 10));
        assertEquals(2, result.entities().size());
        assertEquals(1, result.relationships().size());
    }

    @Test
    void batchUpsertRollsBackEntitiesWhenAnyRelationshipEndpointIsMissing() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeEntity staged = entity("tenant-a", "staged", "Staged");
        assertThrows(RagException.class, () -> store.upsertGraph(
                List.of(staged),
                List.of(relation("tenant-a", "r1", "staged", "missing", "LINKS_TO"))));
        assertTrue(store.findEntity("tenant-a", "policies", "staged").isEmpty());
    }

    @Test
    void batchUpsertRollsBackEarlierWritesWhenLaterIdentityCollisionOccurs() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        store.upsertEntity(entity("tenant-a", "left", "Left"));
        store.upsertEntity(entity("tenant-a", "right", "Right"));
        store.upsertRelationship(relation("tenant-a", "fixed-edge", "left", "right", "LINKS_TO"));
        KnowledgeEntity staged = entity("tenant-a", "staged", "Staged");
        KnowledgeRelationship collision = relation("tenant-a", "fixed-edge", "right", "left", "LINKS_TO");
        assertThrows(RagException.class, () -> store.upsertGraph(List.of(staged), List.of(collision)));
        assertTrue(store.findEntity("tenant-a", "policies", "staged").isEmpty());
    }

    @Test
    void batchUpsertRemainsIdempotentForRepeatedEvidence() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeEntity same = entity("tenant-a", "sales", "Sales");
        store.upsertGraph(List.of(same), List.of());
        store.upsertGraph(List.of(same), List.of());
        assertEquals(1, store.findEntity("tenant-a", "policies", "sales").orElseThrow().evidence().size());
    }

}
