package tech.kayys.wayang.rag;

/**
 * A source citation supporting an extracted graph fact.
 *
 * <p>Offsets are character offsets in the normalized extracted source text. Use -1 when the
 * extractor cannot provide offsets. Evidence is deliberately separate from entity identity:
 * deleting one source must not delete an entity that is still supported by other sources.
 */
public record KnowledgeEvidence(
        String sourceId,
        String sourceVersion,
        String chunkId,
        int startOffset,
        int endOffset,
        String extractor,
        double confidence) {

    public KnowledgeEvidence {
        sourceId = required(sourceId, "sourceId", 200);
        sourceVersion = optional(sourceVersion, 200);
        chunkId = optional(chunkId, 200);
        extractor = optional(extractor, 200);
        if (startOffset < -1 || endOffset < -1 || (startOffset >= 0 && endOffset < startOffset)) {
            throw new RagException("evidence offsets must be -1 or a valid non-negative range");
        }
        if (!Double.isFinite(confidence) || confidence < 0 || confidence > 1) {
            throw new RagException("evidence confidence must be between 0 and 1");
        }
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new RagException(field + " must contain 1.." + max + " characters");
        }
        return value.strip();
    }

    private static String optional(String value, int max) {
        if (value == null || value.isBlank()) return "";
        if (value.length() > max) throw new RagException("evidence field is too long");
        return value.strip();
    }
}
