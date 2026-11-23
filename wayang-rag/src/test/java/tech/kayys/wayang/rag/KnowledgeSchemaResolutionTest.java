package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

class KnowledgeSchemaResolutionTest {

    private static KnowledgeExtractionInput input() {
        String text = "Acme owns Beta and Acme partners Gamma.";
        Document document = new Document("doc-1", "graph.txt", text, Map.of());
        Chunk chunk = new Chunk("chunk-1", "doc-1", "graph.txt", 0, text, "", null, Map.of());
        return new KnowledgeExtractionInput("tenant-a", "collection-a", "v1", document, chunk);
    }

    private static KnowledgeSchema schema() {
        return new KnowledgeSchema("corporate", "1.0",
                Set.of("Organization", "Person"),
                Map.of("OWNS", new RelationshipTypeDefinition(
                                "OWNS", Set.of("Organization"), Set.of("Organization")),
                        "EMPLOYS", new RelationshipTypeDefinition(
                                "EMPLOYS", Set.of("Organization"), Set.of("Person"))));
    }

    private static EntityCandidate entity(String id, String type, String name, int start, int end) {
        return new EntityCandidate(id, type, name, name.toLowerCase(), start, end, 0.9);
    }

    private static RelationshipCandidate relation(
            String id, String from, String predicate, String to, int start, int end) {
        return new RelationshipCandidate(id, from, predicate, to, start, end, 0.8);
    }

    @Test
    void validatesCandidatesAndKeepsValidSubset() {
        EntityCandidate acme = entity("e1", "Organization", "Acme", 0, 4);
        EntityCandidate beta = entity("e2", "Organization", "Beta", 11, 15);
        EntityCandidate invalid = entity("e3", "Unknown", "Ghost", 0, 4);
        RelationshipCandidate owns = relation("r1", "e1", "OWNS", "e2", 0, 15);
        RelationshipCandidate unknownPredicate = relation("r2", "e1", "KNOWS", "e2", 0, 15);

        ValidatedKnowledgeCandidates result = new KnowledgeCandidateValidator().validate(
                input(), List.of(acme, beta, invalid), List.of(owns, unknownPredicate), schema());

        assertEquals(List.of(acme, beta), result.entities());
        assertEquals(List.of(owns), result.relationships());
        assertTrue(result.hasIssues());
        assertEquals(Set.of("UNKNOWN_ENTITY_TYPE", "UNKNOWN_RELATIONSHIP_PREDICATE"),
                result.issues().stream().map(KnowledgeValidationIssue::code).collect(java.util.stream.Collectors.toSet()));
    }

    @Test
    void rejectsRelationshipWhenEndpointTypesDoNotMatchPredicate() {
        EntityCandidate organization = entity("org", "Organization", "Acme", 0, 4);
        EntityCandidate person = entity("person", "Person", "Beta", 11, 15);
        RelationshipCandidate owns = relation("r1", "org", "OWNS", "person", 0, 15);

        ValidatedKnowledgeCandidates result = new KnowledgeCandidateValidator().validate(
                input(), List.of(organization, person), List.of(owns), schema());

        assertTrue(result.relationships().isEmpty());
        assertEquals("RELATIONSHIP_ENDPOINT_TYPE_MISMATCH", result.issues().get(0).code());
    }

    @Test
    void rejectsDanglingDuplicateAndOutOfRangeCandidates() {
        EntityCandidate duplicate = entity("dup", "Organization", "Acme", 0, 4);
        EntityCandidate outOfRange = entity("bad-offset", "Organization", "Bad", 0, 999);
        RelationshipCandidate dangling = relation("r1", "missing", "OWNS", "dup", 0, 4);

        ValidatedKnowledgeCandidates result = new KnowledgeCandidateValidator().validate(
                input(), List.of(duplicate, duplicate, outOfRange), List.of(dangling), schema());

        assertTrue(result.entities().isEmpty());
        assertTrue(result.relationships().isEmpty());
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("DUPLICATE_ENTITY_CANDIDATE_ID")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("ENTITY_OFFSETS_OUT_OF_RANGE")));
        assertTrue(result.issues().stream().anyMatch(i -> i.code().equals("DANGLING_RELATIONSHIP_ENDPOINT")));
    }

    @Test
    void schemaRequiresKnownEndpointTypes() {
        assertThrows(RagException.class, () -> new KnowledgeSchema("bad", "1",
                Set.of("Organization"),
                Map.of("OWNS", new RelationshipTypeDefinition(
                        "OWNS", Set.of("Organization"), Set.of("Person")))));
    }

    @Test
    void exactAliasResolverMatchesOnlyWithinTenantCollectionAndType() {
        EntityCandidate candidate = entity("mention-1", "Organization", "Acme Ltd", 0, 4);
        KnowledgeEntity sameScope = new KnowledgeEntity("tenant-a", "collection-a", "canonical-1",
                "Organization", "Acme Corporation", List.of("Acme Ltd"), Map.of(), List.of());
        KnowledgeEntity otherTenant = new KnowledgeEntity("tenant-b", "collection-a", "secret",
                "Organization", "Acme Ltd", List.of(), Map.of(), List.of());
        KnowledgeEntity otherCollection = new KnowledgeEntity("tenant-a", "collection-b", "other",
                "Organization", "Acme Ltd", List.of(), Map.of(), List.of());
        KnowledgeEntity wrongType = new KnowledgeEntity("tenant-a", "collection-a", "person",
                "Person", "Acme Ltd", List.of(), Map.of(), List.of());

        List<EntityResolutionDecision> result = new ExactAliasEntityResolver().resolve(
                new EntityResolutionRequest("tenant-a", "collection-a", List.of(candidate),
                        List.of(sameScope, otherTenant, otherCollection, wrongType)));

        assertEquals(EntityResolutionDecision.Status.MATCHED, result.get(0).status());
        assertEquals("canonical-1", result.get(0).canonicalEntityId());
    }

    @Test
    void ambiguousExactAliasIsNotMergedAndNoMatchIsNew() {
        EntityCandidate ambiguous = entity("mention-1", "Organization", "Acme", 0, 4);
        EntityCandidate fresh = entity("mention-2", "Organization", "NewCo", 0, 4);
        KnowledgeEntity first = new KnowledgeEntity("tenant-a", "collection-a", "one",
                "Organization", "Acme Inc", List.of("Acme"), Map.of(), List.of());
        KnowledgeEntity second = new KnowledgeEntity("tenant-a", "collection-a", "two",
                "Organization", "Acme Group", List.of("Acme"), Map.of(), List.of());

        List<EntityResolutionDecision> result = new ExactAliasEntityResolver().resolve(
                new EntityResolutionRequest("tenant-a", "collection-a", List.of(ambiguous, fresh),
                        List.of(first, second)));

        assertEquals(EntityResolutionDecision.Status.AMBIGUOUS, result.get(0).status());
        assertNull(result.get(0).canonicalEntityId());
        assertEquals(EntityResolutionDecision.Status.NEW_ENTITY, result.get(1).status());
        assertFalse(result.get(1).status() == EntityResolutionDecision.Status.MATCHED);
    }
}
