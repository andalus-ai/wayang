package tech.kayys.wayang.rag;

/**
 * Model-ranked canonical entity for one mention. runnerUpConfidence is required so callers
 * can enforce a separation margin instead of accepting a merely high but ambiguous score.
 */
public record EntityMatchPrediction(
        String candidateId, String canonicalEntityId, double confidence, double runnerUpConfidence) {
    public EntityMatchPrediction {
        if (candidateId == null || candidateId.isBlank())
            throw new RagException("entity match prediction candidateId is required");
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1
                || !Double.isFinite(runnerUpConfidence) || runnerUpConfidence < 0
                || runnerUpConfidence > confidence) {
            throw new RagException("entity match confidence values must satisfy 0 <= runner-up <= confidence <= 1");
        }
        if (canonicalEntityId != null && (canonicalEntityId.isBlank() || canonicalEntityId.length() > 200))
            throw new RagException("canonical entity ID must contain 1..200 characters when supplied");
    }
}
