package tech.kayys.wayang.rag;

/**
 * An extracted directed relationship candidate. Endpoints refer to candidate IDs from the same
 * extraction result; canonical entity resolution is a later, separate operation.
 */
public record RelationshipCandidate(
        String candidateId,
        String fromCandidateId,
        String predicate,
        String toCandidateId,
        int startOffset,
        int endOffset,
        double confidence) {

    public RelationshipCandidate {
        candidateId = required(candidateId, "candidateId", 200);
        fromCandidateId = required(fromCandidateId, "fromCandidateId", 200);
        predicate = required(predicate, "predicate", 120).toUpperCase(java.util.Locale.ROOT);
        toCandidateId = required(toCandidateId, "toCandidateId", 200);
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new RagException("relationship candidate offsets must be a non-empty range");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new RagException("relationship candidate confidence must be between 0 and 1");
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new RagException(field + " must contain 1.." + max + " characters");
        }
        return value.strip();
    }
}
