package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class ConfidenceAwareEntityResolverTest {
    private static final EntityCandidate MENTION = new EntityCandidate(
            "mention-acme", "Organization", "Acme Corp", "acme corp", 0, 9, 0.95);

    private static KnowledgeEntity entity(String id, String name, List<String> aliases) {
        return new KnowledgeEntity("tenant-a", "docs", id, "Organization", name, aliases,
                Map.of(), List.of(new KnowledgeEvidence("source-" + id, "v1", "chunk-" + id,
                        0, 12, "test", 0.9)));
    }

    private static EntityResolutionRequest request(List<KnowledgeEntity> entities) {
        return new EntityResolutionRequest("tenant-a", "docs", List.of(MENTION), entities);
    }

    private static ConfidenceAwareEntityResolver resolver(
            EntityResolutionModel model, double minimumConfidence, double minimumMargin) {
        return new ConfidenceAwareEntityResolver(model, new ExactAliasEntityResolver(),
                minimumConfidence, minimumMargin);
    }

    @Test
    void acceptsHighConfidenceModelMatchWithinTheCandidateSet() {
        KnowledgeEntity acme = entity("acme", "Acme Corporation", List.of());
        ConfidenceAwareEntityResolver resolver = resolver(
                request -> List.of(new EntityMatchPrediction("mention-acme", "acme", 0.97, 0.12)),
                0.85, 0.10);

        List<EntityResolutionDecision> decisions = resolver.resolve(request(List.of(acme)));

        assertEquals(1, decisions.size());
        assertEquals(EntityResolutionDecision.Status.MATCHED, decisions.get(0).status());
        assertEquals("acme", decisions.get(0).canonicalEntityId());
    }

    @Test
    void lowConfidenceFallsBackToExactAliasResolution() {
        KnowledgeEntity acme = entity("acme", "Acme Corporation", List.of("Acme Corp"));
        ConfidenceAwareEntityResolver resolver = resolver(
                request -> List.of(new EntityMatchPrediction("mention-acme", "acme", 0.60, 0.20)),
                0.85, 0.10);

        EntityResolutionDecision decision = resolver.resolve(request(List.of(acme))).get(0);

        assertEquals(EntityResolutionDecision.Status.MATCHED, decision.status());
        assertEquals("acme", decision.canonicalEntityId());
    }

    @Test
    void narrowWinnerMarginFallsBackAndPreservesAmbiguity() {
        KnowledgeEntity one = entity("acme-1", "Acme One", List.of("Acme Corp"));
        KnowledgeEntity two = entity("acme-2", "Acme Two", List.of("Acme Corp"));
        ConfidenceAwareEntityResolver resolver = resolver(
                request -> List.of(new EntityMatchPrediction("mention-acme", "acme-1", 0.94, 0.90)),
                0.85, 0.10);

        EntityResolutionDecision decision = resolver.resolve(request(List.of(one, two))).get(0);

        assertEquals(EntityResolutionDecision.Status.AMBIGUOUS, decision.status());
        assertNull(decision.canonicalEntityId());
    }

    @Test
    void outOfScopeModelTargetFallsBackInsteadOfBecomingASeed() {
        KnowledgeEntity acme = entity("acme", "Acme Corporation", List.of("Acme Corp"));
        ConfidenceAwareEntityResolver resolver = resolver(
                request -> List.of(new EntityMatchPrediction("mention-acme", "foreign-entity", 0.99, 0.01)),
                0.85, 0.10);

        EntityResolutionDecision decision = resolver.resolve(request(List.of(acme))).get(0);

        assertEquals(EntityResolutionDecision.Status.MATCHED, decision.status());
        assertEquals("acme", decision.canonicalEntityId());
    }

    @Test
    void modelCannotMatchAnEntityOfAnotherType() {
        KnowledgeEntity acme = entity("acme", "Acme Corporation", List.of("Acme Corp"));
        KnowledgeEntity person = new KnowledgeEntity("tenant-a", "docs", "person-1", "Person",
                "Acme Corp", List.of(), Map.of(),
                List.of(new KnowledgeEvidence("source-person", "v1", "chunk-person",
                        0, 12, "test", 0.9)));
        ConfidenceAwareEntityResolver resolver = resolver(
                request -> List.of(new EntityMatchPrediction("mention-acme", "person-1", 0.99, 0.01)),
                0.85, 0.10);

        EntityResolutionDecision decision = resolver.resolve(request(List.of(acme, person))).get(0);

        assertEquals(EntityResolutionDecision.Status.MATCHED, decision.status());
        assertEquals("acme", decision.canonicalEntityId());
    }

    @Test
    void modelFailureFallsBackToExactAliasResolution() {
        KnowledgeEntity acme = entity("acme", "Acme Corporation", List.of("Acme Corp"));
        ConfidenceAwareEntityResolver resolver = resolver(
                request -> { throw new IllegalStateException("provider unavailable"); },
                0.85, 0.10);

        EntityResolutionDecision decision = resolver.resolve(request(List.of(acme))).get(0);

        assertEquals(EntityResolutionDecision.Status.MATCHED, decision.status());
        assertEquals("acme", decision.canonicalEntityId());
    }
}
