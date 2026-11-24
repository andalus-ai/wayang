package tech.kayys.wayang.rag;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * Validates model-extracted mentions and falls back to trusted deterministic rules when
 * the model fails, returns malformed spans, or produces no sufficiently confident mentions.
 */
public final class ModelQueryMentionExtractor implements QueryMentionExtractor {
    private final QueryMentionModel model;
    private final QueryMentionExtractor fallback;
    private final double minimumConfidence;
    private final int maxCandidates;

    public ModelQueryMentionExtractor(QueryMentionModel model, QueryMentionExtractor fallback,
            double minimumConfidence, int maxCandidates) {
        if (model == null || fallback == null)
            throw new RagException("model query extractor requires a model and deterministic fallback");
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0 || minimumConfidence > 1)
            throw new RagException("minimum mention confidence must be between 0 and 1");
        if (maxCandidates < 1 || maxCandidates > QueryEntityLinker.MAX_QUERY_MENTIONS)
            throw new RagException("query mention limit must be 1..100");
        this.model = model;
        this.fallback = fallback;
        this.minimumConfidence = minimumConfidence;
        this.maxCandidates = maxCandidates;
    }

    @Override
    public List<EntityCandidate> extract(String query) {
        if (query == null || query.isBlank()) return List.of();
        if (query.length() > 2_000) throw new RagException("query exceeds 2000 characters");

        final List<QueryMentionPrediction> predictions;
        try {
            predictions = model.extract(query, maxCandidates);
        } catch (RuntimeException modelFailure) {
            return boundedFallback(query);
        }
        if (predictions == null || predictions.isEmpty() || predictions.size() > maxCandidates)
            return boundedFallback(query);

        Map<String, EntityCandidate> unique = new LinkedHashMap<>();
        for (QueryMentionPrediction prediction : predictions) {
            EntityCandidate candidate = validate(query, prediction);
            if (candidate == null) continue;
            String key = candidate.entityType() + "\u0000" + candidate.startOffset()
                    + "\u0000" + candidate.endOffset() + "\u0000" + candidate.normalizedName();
            EntityCandidate previous = unique.get(key);
            if (previous == null || candidate.confidence() > previous.confidence()) unique.put(key, candidate);
        }
        if (unique.isEmpty()) return boundedFallback(query);
        return unique.values().stream()
                .sorted(Comparator.comparingInt(EntityCandidate::startOffset)
                        .thenComparing(EntityCandidate::entityType)
                        .thenComparing(EntityCandidate::candidateId))
                .limit(maxCandidates).toList();
    }

    private EntityCandidate validate(String query, QueryMentionPrediction prediction) {
        if (prediction == null || prediction.entityType() == null || prediction.entityType().isBlank()
                || prediction.entityType().length() > 120 || prediction.surfaceText() == null
                || prediction.surfaceText().isBlank() || prediction.surfaceText().length() > 500
                || prediction.startOffset() < 0 || prediction.endOffset() <= prediction.startOffset()
                || prediction.endOffset() > query.length()
                || !Double.isFinite(prediction.confidence()) || prediction.confidence() < minimumConfidence
                || prediction.confidence() > 1) return null;
        String actual = query.substring(prediction.startOffset(), prediction.endOffset());
        if (!actual.equals(prediction.surfaceText())) return null;
        String normalized = normalize(actual);
        return new EntityCandidate(candidateId(prediction.entityType(), prediction.startOffset(),
                prediction.endOffset(), normalized), prediction.entityType(), actual, normalized,
                prediction.startOffset(), prediction.endOffset(), prediction.confidence());
    }

    private List<EntityCandidate> boundedFallback(String query) {
        List<EntityCandidate> candidates = fallback.extract(query);
        if (candidates == null || candidates.isEmpty()) return List.of();
        if (candidates.size() > maxCandidates)
            return candidates.subList(0, maxCandidates);
        return List.copyOf(candidates);
    }

    private static String normalize(String value) {
        return value.strip().replaceAll("\\s+", " ").toLowerCase(Locale.ROOT);
    }

    private static String candidateId(String type, int start, int end, String name) {
        try {
            String value = type + "\n" + start + "\n" + end + "\n" + name;
            byte[] digest = MessageDigest.getInstance("SHA-256").digest(value.getBytes(StandardCharsets.UTF_8));
            return "query-mention-" + HexFormat.of().formatHex(digest, 0, 16);
        } catch (NoSuchAlgorithmException impossible) {
            throw new RagException("SHA-256 is unavailable for query mention identity", impossible);
        }
    }
}
