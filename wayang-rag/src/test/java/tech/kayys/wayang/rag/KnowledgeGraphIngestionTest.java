package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeGraphIngestionTest {

    private static final String TEXT = "Acme owns Beta.";

    private static KnowledgeExtractionInput input(String source, String version, String chunkId) {
        Document document = new Document(source, source + ".txt", TEXT, Map.of());
        Chunk chunk = new Chunk(chunkId, source, source + ".txt", 0, TEXT, "", null, Map.of());
        return new KnowledgeExtractionInput("tenant-a", "collection-a", version, document, chunk);
    }

    private static EntityCandidate acme() {
        return new EntityCandidate("e-acme", "Organization", "Acme", "acme", 0, 4, 0.99);
    }

    private static EntityCandidate beta() {
        return new EntityCandidate("e-beta", "Organization", "Beta", "beta", 10, 14, 0.98);
    }

    private static RelationshipCandidate owns() {
        return new RelationshipCandidate("r-owns", "e-acme", "OWNS", "e-beta", 0, 14, 0.95);
    }

    private static ValidatedKnowledgeCandidates validated() {
        return new ValidatedKnowledgeCandidates(List.of(acme(), beta()), List.of(owns()), List.of());
    }

    private static List<EntityResolutionDecision> newEntities() {
        return List.of(
                new EntityResolutionDecision("e-acme", EntityResolutionDecision.Status.NEW_ENTITY, null),
                new EntityResolutionDecision("e-beta", EntityResolutionDecision.Status.NEW_ENTITY, null));
    }

    private static String entityId(String type, String normalizedName) throws NoSuchAlgorithmException {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        update(digest, "entity");
        update(digest, "tenant-a");
        update(digest, "collection-a");
        update(digest, type);
        update(digest, normalizedName);
        return "entity-" + HexFormat.of().formatHex(digest.digest(), 0, 20);
    }

    private static void update(MessageDigest digest, String value) {
        byte[] bytes = value.getBytes(StandardCharsets.UTF_8);
        digest.update((byte) (bytes.length >>> 24));
        digest.update((byte) (bytes.length >>> 16));
        digest.update((byte) (bytes.length >>> 8));
        digest.update((byte) bytes.length);
        digest.update(bytes);
    }

    private static GraphTraversalResult traverse(InMemoryGraphStore store) {
        return store.traverse(new GraphQuery("tenant-a", "collection-a", Set.of(
                entityIdUnchecked("acme")), GraphQuery.Direction.OUTGOING, Set.of(), 1, 10, 10));
    }

    private static String entityIdUnchecked(String name) {
        try {
            return entityId("Organization", name);
        } catch (NoSuchAlgorithmException e) {
            throw new AssertionError(e);
        }
    }

    @Test
    void ingestionIsIdempotentAndAccumulatesIndependentSourceEvidence() throws Exception {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeGraphIngestionService service = new KnowledgeGraphIngestionService(store);

        KnowledgeGraphIngestionResult first = service.ingest(
                input("source-a", "v1", "chunk-a"), validated(), newEntities(), "test-extractor");
        KnowledgeGraphIngestionResult repeated = service.ingest(
                input("source-a", "v1", "chunk-a"), validated(), newEntities(), "test-extractor");
        KnowledgeGraphIngestionResult secondSource = service.ingest(
                input("source-b", "v1", "chunk-b"), validated(), newEntities(), "test-extractor");

        assertEquals(2, first.entitiesUpserted());
        assertEquals(1, first.relationshipsUpserted());
        assertEquals(2, repeated.entitiesUpserted());
        assertEquals(1, repeated.relationshipsUpserted());
        assertEquals(2, secondSource.entitiesUpserted());

        GraphTraversalResult graph = traverse(store);
        assertEquals(2, graph.entities().size());
        assertEquals(1, graph.relationships().size());
        assertEquals(2, graph.entities().get(0).evidence().size());
        assertEquals(2, graph.relationships().get(0).evidence().size());
    }

    @Test
    void retractingOneSourceVersionPreservesOtherEvidenceAndRelationship() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeGraphIngestionService service = new KnowledgeGraphIngestionService(store);
        service.ingest(input("source-a", "v1", "chunk-a"), validated(), newEntities(), "extractor");
        service.ingest(input("source-b", "v1", "chunk-b"), validated(), newEntities(), "extractor");

        EvidenceRetractionResult result =
                service.retractSourceVersion("tenant-a", "collection-a", "source-a", "v1");

        assertEquals(2, result.entityEvidenceRemoved());
        assertEquals(1, result.relationshipEvidenceRemoved());
        assertEquals(0, result.relationshipsRemoved());
        GraphTraversalResult graph = traverse(store);
        assertEquals(2, graph.entities().size());
        assertEquals(1, graph.relationships().size());
        assertTrue(graph.entities().stream().allMatch(e -> e.evidence().size() == 1));
        assertEquals("source-b", graph.relationships().get(0).evidence().get(0).sourceId());
    }

    @Test
    void relationshipIsRemovedOnlyAfterItsLastEvidenceIsRetracted() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeGraphIngestionService service = new KnowledgeGraphIngestionService(store);
        service.ingest(input("source-a", "v1", "chunk-a"), validated(), newEntities(), "extractor");

        EvidenceRetractionResult result =
                service.retractSourceVersion("tenant-a", "collection-a", "source-a", "v1");

        assertEquals(2, result.entityEvidenceRemoved());
        assertEquals(1, result.relationshipEvidenceRemoved());
        assertEquals(1, result.relationshipsRemoved());
        assertTrue(traverse(store).relationships().isEmpty());
        // Canonical entities are retained even when source evidence is gone.
        assertNotNull(store.findEntity("tenant-a", "collection-a", entityIdUnchecked("acme")).orElse(null));
    }

    @Test
    void ambiguousOrMissingResolutionDoesNotCreateEntityOrRelationship() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeGraphIngestionService service = new KnowledgeGraphIngestionService(store);
        List<EntityResolutionDecision> resolutions = List.of(
                new EntityResolutionDecision("e-acme", EntityResolutionDecision.Status.AMBIGUOUS, null));

        KnowledgeGraphIngestionResult result = service.ingest(
                input("source-a", "v1", "chunk-a"), validated(), resolutions, "extractor");

        assertEquals(0, result.entitiesUpserted());
        assertEquals(0, result.relationshipsUpserted());
        assertEquals(2, result.unresolvedEntities());
        assertEquals(1, result.skippedRelationships());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("AMBIGUOUS_ENTITY_RESOLUTION")));
        assertTrue(store.findEntity("tenant-a", "collection-a", entityIdUnchecked("acme")).isEmpty());
    }

    @Test
    void matchedResolutionMustReferToExistingEntityInTheSameScope() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeGraphIngestionService service = new KnowledgeGraphIngestionService(store);
        List<EntityResolutionDecision> resolutions = List.of(
                new EntityResolutionDecision("e-acme", EntityResolutionDecision.Status.MATCHED, "foreign-id"),
                new EntityResolutionDecision("e-beta", EntityResolutionDecision.Status.NEW_ENTITY, null));

        KnowledgeGraphIngestionResult result = service.ingest(
                input("source-a", "v1", "chunk-a"), validated(), resolutions, "extractor");

        assertEquals(1, result.unresolvedEntities());
        assertEquals(0, result.relationshipsUpserted());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("RESOLVED_ENTITY_NOT_FOUND")));
    }

    @Test
    void retractionIsScopedToTenantCollectionSourceAndExactVersion() {
        InMemoryGraphStore store = new InMemoryGraphStore();
        KnowledgeGraphIngestionService service = new KnowledgeGraphIngestionService(store);
        service.ingest(input("source-a", "v1", "chunk-a"), validated(), newEntities(), "extractor");

        EvidenceRetractionResult wrongVersion =
                service.retractSourceVersion("tenant-a", "collection-a", "source-a", "v2");
        EvidenceRetractionResult wrongTenant =
                service.retractSourceVersion("tenant-b", "collection-a", "source-a", "v1");
        EvidenceRetractionResult correct =
                service.retractSourceVersion("tenant-a", "collection-a", "source-a", "v1");

        assertEquals(0, wrongVersion.entityEvidenceRemoved());
        assertEquals(0, wrongTenant.entityEvidenceRemoved());
        assertEquals(2, correct.entityEvidenceRemoved());
        assertThrows(RagException.class,
                () -> service.retractSourceVersion("tenant-a", "collection-a", " ", "v1"));
    }
}
