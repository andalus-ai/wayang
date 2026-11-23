package tech.kayys.wayang.rag;

/** A machine-readable validation finding; invalid candidates are omitted from valid output. */
public record KnowledgeValidationIssue(String candidateId, String code, String message) {
    public KnowledgeValidationIssue {
        if (code == null || code.isBlank()) throw new RagException("validation issue code is required");
        if (message == null || message.isBlank()) throw new RagException("validation issue message is required");
    }
}
