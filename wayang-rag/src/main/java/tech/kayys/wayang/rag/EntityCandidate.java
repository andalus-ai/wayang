package tech.kayys.wayang.rag;

/**
 * An extracted entity mention, not yet a canonical graph entity.
 * Offsets are zero-based, end-exclusive character offsets within the supplied chunk.
 */
public record EntityCandidate(
        String candidateId,
        String entityType,
        String surfaceText,
        String normalizedName,
        int startOffset,
        int endOffset,
        double confidence) {

    public EntityCandidate {
        candidateId = required(candidateId, "candidateId", 200);
        entityType = required(entityType, "entityType", 120);
        surfaceText = required(surfaceText, "surfaceText", 500);
        normalizedName = required(normalizedName, "normalizedName", 500);
        if (startOffset < 0 || endOffset <= startOffset) {
            throw new RagException("entity candidate offsets must be a non-empty range");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new RagException("entity candidate confidence must be between 0 and 1");
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new RagException(field + " must contain 1.." + max + " characters");
        }
        return value.strip();
    }
}
