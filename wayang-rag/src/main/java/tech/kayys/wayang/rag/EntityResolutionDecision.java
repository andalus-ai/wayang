package tech.kayys.wayang.rag;

/** Conservative resolution outcome for one extracted mention. */
public record EntityResolutionDecision(String candidateId, Status status, String canonicalEntityId) {
    public enum Status { MATCHED, NEW_ENTITY, AMBIGUOUS }
    public EntityResolutionDecision {
        if (candidateId == null || candidateId.isBlank()) throw new RagException("candidateId is required");
        if (status == null) throw new RagException("resolution status is required");
        if (status == Status.MATCHED && (canonicalEntityId == null || canonicalEntityId.isBlank())) {
            throw new RagException("matched resolution requires a canonical entity ID");
        }
        if (status != Status.MATCHED && canonicalEntityId != null) {
            throw new RagException("only matched resolutions may include a canonical entity ID");
        }
    }
}
