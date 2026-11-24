package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.regex.Pattern;

import org.junit.jupiter.api.Test;

class ModelQueryMentionExtractorTest {
    private static final String QUERY = "Compare Acme Corp with Globex";
    private static final RegexQueryMentionExtractor FALLBACK = new RegexQueryMentionExtractor(
            List.of(new RegexQueryMentionExtractor.Rule(
                    "Organization", Pattern.compile("(?i)(Acme Corp)"), 1, 0.90)), 10);

    @Test
    void acceptsOnlyModelSpansThatMatchTheOriginalQuery() {
        int start = QUERY.indexOf("Acme Corp");
        ModelQueryMentionExtractor extractor = new ModelQueryMentionExtractor(
                (query, limit) -> List.of(new QueryMentionPrediction(
                        "Organization", "Acme Corp", start, start + "Acme Corp".length(), 0.97)),
                FALLBACK, 0.80, 10);

        List<EntityCandidate> result = extractor.extract(QUERY);

        assertEquals(1, result.size());
        assertEquals("Acme Corp", result.get(0).surfaceText());
        assertEquals(start, result.get(0).startOffset());
        assertEquals(0.97, result.get(0).confidence());
    }

    @Test
    void malformedOrLowConfidenceModelOutputUsesDeterministicFallback() {
        ModelQueryMentionExtractor malformed = new ModelQueryMentionExtractor(
                (query, limit) -> List.of(new QueryMentionPrediction(
                        "Organization", "Invented Corp", 8, 17, 0.99)),
                FALLBACK, 0.80, 10);
        ModelQueryMentionExtractor lowConfidence = new ModelQueryMentionExtractor(
                (query, limit) -> List.of(new QueryMentionPrediction(
                        "Organization", "Acme Corp", 8, 17, 0.20)),
                FALLBACK, 0.80, 10);

        assertEquals("Acme Corp", malformed.extract(QUERY).get(0).surfaceText());
        assertEquals("Acme Corp", lowConfidence.extract(QUERY).get(0).surfaceText());
        assertEquals(0.90, lowConfidence.extract(QUERY).get(0).confidence());
    }

    @Test
    void modelFailureUsesDeterministicFallback() {
        ModelQueryMentionExtractor extractor = new ModelQueryMentionExtractor(
                (query, limit) -> { throw new IllegalStateException("provider unavailable"); },
                FALLBACK, 0.80, 10);

        assertEquals("Acme Corp", extractor.extract(QUERY).get(0).surfaceText());
    }

    @Test
    void rejectsQueriesOverTheSharedBoundBeforeCallingModel() {
        ModelQueryMentionExtractor extractor = new ModelQueryMentionExtractor(
                (query, limit) -> { throw new AssertionError("model must not be called"); },
                FALLBACK, 0.80, 10);

        assertThrows(RagException.class, () -> extractor.extract("x".repeat(2_001)));
    }
}
