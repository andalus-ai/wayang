package tech.kayys.wayang.rag;

/** Immutable, tenant-scoped input for graph extraction from one already-normalized document chunk. */
public record KnowledgeExtractionInput(
        String tenantId,
        String collectionId,
        String sourceVersion,
        Document document,
        Chunk chunk) {

    public KnowledgeExtractionInput {
        tenantId = required(tenantId, "tenantId", 200);
        collectionId = required(collectionId, "collectionId", 200);
        sourceVersion = required(sourceVersion, "sourceVersion", 200);
        if (document == null) throw new RagException("document is required for knowledge extraction");
        if (chunk == null) throw new RagException("chunk is required for knowledge extraction");
        if (!document.id().equals(chunk.docId())) {
            throw new RagException("chunk document id does not match extraction document");
        }
        if (chunk.id() == null || chunk.id().isBlank() || chunk.id().length() > 200) {
            throw new RagException("chunk needs an id of 1..200 characters for knowledge extraction");
        }
        if (chunk.text() == null) throw new RagException("chunk text is required for knowledge extraction");
    }

    private static String required(String value, String field, int max) {
        if (value == null || value.isBlank() || value.length() > max) {
            throw new RagException(field + " must contain 1.." + max + " characters");
        }
        return value.strip();
    }
}
