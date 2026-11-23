package tech.kayys.wayang.rag;

/** Counts the source-scoped evidence removed by one graph retraction operation. */
public record EvidenceRetractionResult(
        int entityEvidenceRemoved,
        int relationshipEvidenceRemoved,
        int relationshipsRemoved) {
    public EvidenceRetractionResult {
        if (entityEvidenceRemoved < 0 || relationshipEvidenceRemoved < 0 || relationshipsRemoved < 0) {
            throw new RagException("evidence retraction counts cannot be negative");
        }
    }
}
