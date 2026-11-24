package tech.kayys.wayang.rag;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

/**
 * Uses model rankings only when both an absolute confidence threshold and a winner margin
 * are met. Uncertain predictions fall back to exact alias resolution; malformed/model-failed
 * responses fall back for the entire request.
 */
public final class ConfidenceAwareEntityResolver implements EntityResolver {
    private final EntityResolutionModel model;
    private final EntityResolver fallback;
    private final double minimumConfidence;
    private final double minimumMargin;

    public ConfidenceAwareEntityResolver(EntityResolutionModel model, EntityResolver fallback,
            double minimumConfidence, double minimumMargin) {
        if (model == null || fallback == null)
            throw new RagException("confidence-aware resolver requires a model and deterministic fallback");
        if (!Double.isFinite(minimumConfidence) || minimumConfidence < 0 || minimumConfidence > 1
                || !Double.isFinite(minimumMargin) || minimumMargin < 0 || minimumMargin > 1)
            throw new RagException("resolution confidence and margin thresholds must be between 0 and 1");
        this.model = model;
        this.fallback = fallback;
        this.minimumConfidence = minimumConfidence;
        this.minimumMargin = minimumMargin;
    }

    @Override
    public List<EntityResolutionDecision> resolve(EntityResolutionRequest request) {
        if (request == null) throw new RagException("entity resolution request is required");
        if (request.candidates().isEmpty() || request.existingEntities().isEmpty())
            return fallback.resolve(request);

        final List<EntityMatchPrediction> predictions;
        try {
            predictions = model.rank(request);
        } catch (RuntimeException modelFailure) {
            return fallback.resolve(request);
        }
        if (predictions == null || predictions.size() != request.candidates().size())
            return fallback.resolve(request);

        Set<String> expected = new HashSet<>();
        request.candidates().forEach(c -> expected.add(c.candidateId()));
        Map<String, KnowledgeEntity> existingById = new HashMap<>();
        request.existingEntities().forEach(e -> existingById.put(e.id(), e));
        Map<String, EntityMatchPrediction> byCandidate = new HashMap<>();
        try {
            for (EntityMatchPrediction prediction : predictions) {
                if (prediction == null || !expected.contains(prediction.candidateId())
                        || byCandidate.putIfAbsent(prediction.candidateId(), prediction) != null
                        || (prediction.canonicalEntityId() != null
                            && (!existingById.containsKey(prediction.canonicalEntityId())
                                || !existingById.get(prediction.canonicalEntityId()).type().equals(
                                    request.candidates().stream()
                                        .filter(candidate -> candidate.candidateId().equals(prediction.candidateId()))
                                        .findFirst().orElseThrow().entityType())))) {
                    return fallback.resolve(request);
                }
            }
        } catch (RuntimeException malformedPrediction) {
            return fallback.resolve(request);
        }
        if (!byCandidate.keySet().equals(expected)) return fallback.resolve(request);

        List<EntityResolutionDecision> deterministic = fallback.resolve(request);
        Map<String, EntityResolutionDecision> fallbackByCandidate = new HashMap<>();
        for (EntityResolutionDecision decision : deterministic) {
            if (decision == null || fallbackByCandidate.putIfAbsent(decision.candidateId(), decision) != null)
                return fallback.resolve(request);
        }

        List<EntityResolutionDecision> result = new ArrayList<>(request.candidates().size());
        for (EntityCandidate candidate : request.candidates()) {
            EntityMatchPrediction prediction = byCandidate.get(candidate.candidateId());
            double margin = prediction.confidence() - prediction.runnerUpConfidence();
            if (prediction.canonicalEntityId() != null
                    && prediction.confidence() >= minimumConfidence && margin >= minimumMargin) {
                result.add(new EntityResolutionDecision(candidate.candidateId(),
                        EntityResolutionDecision.Status.MATCHED, prediction.canonicalEntityId()));
            } else {
                EntityResolutionDecision exact = fallbackByCandidate.get(candidate.candidateId());
                if (exact == null) return fallback.resolve(request);
                result.add(exact);
            }
        }
        return List.copyOf(result);
    }
}
