package tech.kayys.wayang.rag;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;

import org.junit.jupiter.api.Test;

/**
 * Small, deterministic golden-set evaluation for query mention extraction.
 *
 * <p>This measures span/type precision and recall of the extraction contract. It deliberately
 * uses a fake model adapter: provider quality must be evaluated with a separately versioned,
 * opt-in dataset and the real provider adapter, never inferred from this unit test.</p>
 */
class QueryUnderstandingEvaluationTest {

    private record GoldMention(String type, String surface) {}
    private record Fixture(String query, List<GoldMention> expected, List<GoldMention> predicted) {}
    private record Metrics(int truePositives, int falsePositives, int falseNegatives) {
        double precision() {
            return truePositives + falsePositives == 0 ? 1.0
                    : (double) truePositives / (truePositives + falsePositives);
        }

        double recall() {
            return truePositives + falseNegatives == 0 ? 1.0
                    : (double) truePositives / (truePositives + falseNegatives);
        }

        double f1() {
            return precision() + recall() == 0 ? 0
                    : 2 * precision() * recall() / (precision() + recall());
        }
    }

    private static final List<Fixture> FIXTURES = List.of(
            new Fixture("Compare Acme Corp with Globex Inc.",
                    List.of(new GoldMention("Organization", "Acme Corp"),
                            new GoldMention("Organization", "Globex Inc.")),
                    List.of(new GoldMention("Organization", "Acme Corp"),
                            new GoldMention("Organization", "Globex Inc."))),
            new Fixture("Summarize the Contoso policy.",
                    List.of(new GoldMention("Organization", "Contoso")),
                    List.of()),
            new Fixture("Does Northwind own the Orion platform or unknown vendor?",
                    List.of(new GoldMention("Organization", "Northwind"),
                            new GoldMention("Product", "Orion platform")),
                    List.of(new GoldMention("Organization", "Northwind"),
                            new GoldMention("Product", "Orion platform"),
                            new GoldMention("Organization", "unknown vendor"))));

    @Test
    void reportsSpanAndTypePrecisionRecallAgainstGoldenFixtures() {
        List<QueryMentionPrediction> predictions = new ArrayList<>();
        List<ExpectedSpan> expected = new ArrayList<>();
        List<ExpectedSpan> predicted = new ArrayList<>();

        for (Fixture fixture : FIXTURES) {
            fixture.expected().forEach(m -> expected.add(span(fixture.query(), m)));
            fixture.predicted().forEach(m -> {
                ExpectedSpan span = span(fixture.query(), m);
                predicted.add(span);
                predictions.add(new QueryMentionPrediction(m.type(), m.surface(),
                        span.start(), span.end(), 0.99));
            });
        }

        // The extractor is evaluated with predictions grouped by query, preserving its normal API.
        List<ExpectedSpan> actual = new ArrayList<>();
        for (Fixture fixture : FIXTURES) {
            List<QueryMentionPrediction> queryPredictions = predictionsFor(fixture);
            ModelQueryMentionExtractor extractor = new ModelQueryMentionExtractor(
                    (query, limit) -> queryPredictions, query -> List.of(), 0.80, 20);
            for (EntityCandidate candidate : extractor.extract(fixture.query())) {
                actual.add(new ExpectedSpan(fixture.query(), candidate.entityType(),
                        candidate.startOffset(), candidate.endOffset(), candidate.surfaceText()));
            }
        }

        Metrics metrics = evaluate(expected, actual);
        assertEquals(4, metrics.truePositives());
        assertEquals(1, metrics.falsePositives());
        assertEquals(1, metrics.falseNegatives());
        assertEquals(0.80, metrics.precision(), 0.0001);
        assertEquals(0.80, metrics.recall(), 0.0001);
        assertEquals(0.80, metrics.f1(), 0.0001);
    }

    @Test
    void rejectsInvalidSpansInsteadOfCountingThemAsExtractedMentions() {
        String query = "Ask about Acme Corp";
        ModelQueryMentionExtractor extractor = new ModelQueryMentionExtractor(
                (text, limit) -> List.of(
                        new QueryMentionPrediction("Organization", "Invented Corp", 15, 26, 0.99),
                        new QueryMentionPrediction("Organization", "Acme Corp", 15, 24, 0.99)),
                text -> List.of(), 0.80, 10);

        List<EntityCandidate> result = extractor.extract(query);

        assertEquals(1, result.size());
        assertEquals("Acme Corp", result.get(0).surfaceText());
    }

    private static List<QueryMentionPrediction> predictionsFor(Fixture fixture) {
        return fixture.predicted().stream().map(mention -> {
            ExpectedSpan span = span(fixture.query(), mention);
            return new QueryMentionPrediction(mention.type(), mention.surface(),
                    span.start(), span.end(), 0.99);
        }).toList();
    }

    private record ExpectedSpan(String query, String type, int start, int end, String surface) {
        String key() {
            return type + "\u0000" + start + "\u0000" + end + "\u0000" + surface;
        }
    }

    private static ExpectedSpan span(String query, GoldMention mention) {
        int start = query.indexOf(mention.surface());
        if (start < 0) throw new AssertionError("Fixture mention is not present in query: " + mention);
        return new ExpectedSpan(query, mention.type(), start, start + mention.surface().length(), mention.surface());
    }

    private static Metrics evaluate(List<ExpectedSpan> gold, List<ExpectedSpan> actual) {
        Set<String> goldKeys = new HashSet<>();
        gold.forEach(span -> goldKeys.add(span.query() + "\u0000" + span.key()));
        Set<String> actualKeys = new HashSet<>();
        actual.forEach(span -> actualKeys.add(span.query() + "\u0000" + span.key()));
        Set<String> intersection = new HashSet<>(goldKeys);
        intersection.retainAll(actualKeys);
        return new Metrics(intersection.size(), actualKeys.size() - intersection.size(),
                goldKeys.size() - intersection.size());
    }
}
