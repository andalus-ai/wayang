package tech.kayys.wayang.rag;

import java.util.List;
import java.util.Map;

/**
 * The JSON-shaped definition of a knowledge base, as an author would write it:
 * <pre>{@code {"chunking": {"type": "heading", "maxChars": 1500},
 *  "retrieval": {"method": "hybrid", "topK": 5, "mmr": {"lambda": 0.7}, "expand": "none"},
 *  "maxDocuments": 200, "maxChunks": 10000}}</pre>
 * Unknown keys are rejected so a typo cannot silently fall back to a default.
 */
public record KnowledgeConfig(Chunker chunker, Map<String, Object> retrieval, int maxDocuments, int maxChunks) {

    @SuppressWarnings("unchecked")
    public static KnowledgeConfig fromMap(Map<String, Object> c) {
        Map<String, Object> m = c == null ? Map.of() : c;
        for (String k : m.keySet()) {
            if (!List.of("chunking", "retrieval", "maxDocuments", "maxChunks").contains(k)) {
                throw new RagException("the knowledge definition has an unknown key '" + k + "'");
            }
        }
        Object ch = m.get("chunking");
        Object rt = m.get("retrieval");
        if (ch != null && !(ch instanceof Map)) {
            throw new RagException("chunking must be an object");
        }
        if (rt != null && !(rt instanceof Map)) {
            throw new RagException("retrieval must be an object");
        }
        Chunker chunker = Chunkers.fromConfig(ch == null ? Map.of() : (Map<String, Object>) ch);
        Map<String, Object> retrieval = rt == null ? Map.of() : (Map<String, Object>) rt;
        RetrievalConfig.fromMap(retrieval, true); // validate the shape now; the embedder question is answered by build()
        return new KnowledgeConfig(chunker, retrieval,
                Chunkers.intOf(m, "maxDocuments", KnowledgeBase.DEFAULT_MAX_DOCUMENTS), Chunkers.intOf(m, "maxChunks", KnowledgeBase.DEFAULT_MAX_CHUNKS));
    }

    /** @param embedder null for keyword-only retrieval */
    public KnowledgeBase build(Embedder embedder) {
        RetrievalConfig.fromMap(retrieval, embedder != null);
        return new KnowledgeBase(chunker, embedder, maxDocuments, maxChunks);
    }

    /** The search settings to use with {@link KnowledgeBase#search} when the caller passes none. */
    public RetrievalConfig defaultRetrieval(boolean hasEmbedder) {
        return RetrievalConfig.fromMap(retrieval, hasEmbedder);
    }
}
