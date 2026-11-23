package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.List;
import java.util.Map;
import java.util.regex.Pattern;
import org.junit.jupiter.api.Test;

class KnowledgeExtractionTest {

    private static KnowledgeExtractionInput input(String sourceText) {
        Document document = new Document("doc-1", "ownership.txt", sourceText, Map.of());
        Chunk chunk = new Chunk("chunk-1", "doc-1", "ownership.txt", 0, sourceText, "", null, Map.of());
        return new KnowledgeExtractionInput("tenant-a", "collection-a", "v1", document, chunk);
    }

    private static RegexEntityExtractor entityExtractor() {
        return new RegexEntityExtractor(List.of(
                new RegexEntityExtractor.Rule("Organization", Pattern.compile("\\b(Acme|Beta|Gamma)\\b"), 1, 0.95)));
    }

    private static RegexRelationshipExtractor relationshipExtractor() {
        return new RegexRelationshipExtractor(List.of(
                new RegexRelationshipExtractor.Rule("OWNS",
                        Pattern.compile("(Acme) owns (Beta|Gamma)"), 1, 2, 0.9)));
    }

    @Test
    void extractsDeterministicEntityCandidatesWithChunkLocalOffsets() {
        KnowledgeExtractionInput input = input("Acme owns Beta.");
        List<EntityCandidate> first = entityExtractor().extract(input);
        List<EntityCandidate> second = entityExtractor().extract(input);

        assertEquals(2, first.size());
        assertEquals(first, second);
        assertEquals("Acme", first.get(0).surfaceText());
        assertEquals(0, first.get(0).startOffset());
        assertEquals(4, first.get(0).endOffset());
        assertTrue(first.get(0).candidateId().startsWith("mention-"));
    }

    @Test
    void relationshipCandidatesReferenceExactEntityMentions() {
        KnowledgeExtractionInput input = input("Acme owns Beta. Acme owns Gamma.");
        List<EntityCandidate> entities = entityExtractor().extract(input);
        List<RelationshipCandidate> relationships = relationshipExtractor().extract(input, entities);

        assertEquals(2, relationships.size());
        assertEquals("OWNS", relationships.get(0).predicate());
        assertEquals(entities.stream().filter(e -> e.surfaceText().equals("Acme") && e.startOffset() == 0)
                .findFirst().orElseThrow().candidateId(), relationships.get(0).fromCandidateId());
        assertEquals(entities.stream().filter(e -> e.surfaceText().equals("Beta"))
                .findFirst().orElseThrow().candidateId(), relationships.get(0).toCandidateId());
        assertNotEquals(relationships.get(0).candidateId(), relationships.get(1).candidateId());
    }

    @Test
    void relationshipExtractorSkipsWhenEndpointMentionIsNotPresent() {
        KnowledgeExtractionInput input = input("Acme owns Beta.");
        assertTrue(relationshipExtractor().extract(input, List.of()).isEmpty());
        assertTrue(relationshipExtractor().extract(input,
                List.of(new EntityCandidate("only-acme", "Organization", "Acme", "acme", 0, 4, 0.9))).isEmpty());
    }

    @Test
    void enforcesCandidateLimits() {
        RegexEntityExtractor extractor = new RegexEntityExtractor(List.of(
                new RegexEntityExtractor.Rule("Organization", Pattern.compile("\\b(Acme|Beta|Gamma)\\b"), 1, 0.9)), 1);
        assertEquals(1, extractor.extract(input("Acme owns Beta and Gamma.")).size());
    }

    @Test
    void validatesExtractionScopeAndRuleGroups() {
        Document document = new Document("doc-a", "a.txt", "Acme", Map.of());
        Chunk otherDocumentChunk = new Chunk("chunk", "doc-b", "b.txt", 0, "Acme", "", null, Map.of());
        assertThrows(RagException.class,
                () -> new KnowledgeExtractionInput("tenant-a", "collection-a", "v1", document, otherDocumentChunk));
        assertThrows(RagException.class, () -> new RegexRelationshipExtractor.Rule(
                "OWNS", Pattern.compile("(Acme)"), 1, 1, 0.9));
    }

    @Test
    void candidateIdentityIsScopedBySourceVersionAndTenant() {
        RegexEntityExtractor extractor = entityExtractor();
        EntityCandidate base = extractor.extract(input("Acme owns Beta.")).get(0);

        Document document = new Document("doc-1", "ownership.txt", "Acme owns Beta.", Map.of());
        Chunk chunk = new Chunk("chunk-1", "doc-1", "ownership.txt", 0, "Acme owns Beta.", "", null, Map.of());
        EntityCandidate nextVersion = extractor.extract(new KnowledgeExtractionInput(
                "tenant-a", "collection-a", "v2", document, chunk)).get(0);
        EntityCandidate otherTenant = extractor.extract(new KnowledgeExtractionInput(
                "tenant-b", "collection-a", "v1", document, chunk)).get(0);

        assertNotEquals(base.candidateId(), nextVersion.candidateId());
        assertNotEquals(base.candidateId(), otherTenant.candidateId());
    }
}
