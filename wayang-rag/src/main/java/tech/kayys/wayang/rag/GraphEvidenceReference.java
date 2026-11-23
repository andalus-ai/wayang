package tech.kayys.wayang.rag;

/** A provenance pointer emitted by graph traversal and resolved back to an authorized passage. */
public record GraphEvidenceReference(String tenantId, String collectionId, String entityId,
        String relationshipId, KnowledgeEvidence evidence) {
    public GraphEvidenceReference {
        if (tenantId == null || tenantId.isBlank() || collectionId == null || collectionId.isBlank())
            throw new RagException("graph evidence reference requires tenant and collection scope");
        if ((entityId == null || entityId.isBlank()) && (relationshipId == null || relationshipId.isBlank()))
            throw new RagException("graph evidence reference requires an entity or relationship ID");
        if (evidence == null) throw new RagException("graph evidence reference requires evidence");
    }
}
