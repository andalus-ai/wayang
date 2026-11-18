package tech.kayys.wayang.rag;

import java.util.List;
import java.util.Locale;
import java.util.Map;

/**
 * How a knowledge base is searched.
 *
 * <ul>
 * <li>{@code bm25}: keyword ranking (BM25). Needs no model, exact terms such as article numbers and names match well, but synonyms do not.</li>
 * <li>{@code vector}: nearest chunks by embedding similarity; needs an {@link Embedder}. Finds paraphrases; can miss exact identifiers.</li>
 * <li>{@code hybrid}: reciprocal-rank fusion of both; needs an {@link Embedder}. The usual best general choice when one is available.</li>
 * </ul>
 * Optional {@code mmr} (maximal marginal relevance, {@code lambda} 0..1, higher favours relevance) removes near-duplicate hits, and {@code expand: parent}
 * returns the parent window of a parent-child chunk instead of the small chunk.
 */
public record RetrievalConfig(Method method, int topK, Double mmrLambda, boolean expandParent) {

    public enum Method { BM25, VECTOR, HYBRID }

    public static final int MAX_TOP_K = 50;

    public RetrievalConfig {
        if (method == null) {
            throw new RagException("retrieval needs a method");
        }
        if (topK < 1 || topK > MAX_TOP_K) {
            throw new RagException("topK must be between 1 and " + MAX_TOP_K);
        }
        if (mmrLambda != null && (mmrLambda < 0 || mmrLambda > 1)) {
            throw new RagException("mmr lambda must be between 0 and 1");
        }
    }

    public static RetrievalConfig defaults(boolean hasEmbedder) {
        return new RetrievalConfig(hasEmbedder ? Method.HYBRID : Method.BM25, 5, null, false);
    }

    public static RetrievalConfig fromMap(Map<String, Object> c, boolean hasEmbedder) {
        if (c == null || c.isEmpty()) {
            return defaults(hasEmbedder);
        }
        for (String k : c.keySet()) {
            if (!List.of("method", "topK", "mmr", "expand").contains(k)) {
                throw new RagException("retrieval has an unknown setting '" + k + "'");
            }
        }
        Method m;
        if (c.get("method") instanceof String s) {
            try {
                m = Method.valueOf(s.toUpperCase(Locale.ROOT).replace('-', '_'));
            } catch (IllegalArgumentException e) {
                throw new RagException("unknown retrieval method '" + s + "' (bm25, vector, hybrid)");
            }
        } else if (c.get("method") == null) {
            m = hasEmbedder ? Method.HYBRID : Method.BM25;
        } else {
            throw new RagException("method must be text");
        }
        Double lambda = null;
        Object mmr = c.get("mmr");
        if (mmr instanceof Map<?, ?> mm) {
            for (Object k : mm.keySet()) {
                if (!"lambda".equals(k)) {
                    throw new RagException("mmr has an unknown setting '" + k + "'");
                }
            }
            lambda = mm.get("lambda") instanceof Number n ? n.doubleValue() : 0.7;
        } else if (Boolean.TRUE.equals(mmr)) {
            lambda = 0.7;
        } else if (mmr != null && !Boolean.FALSE.equals(mmr)) {
            throw new RagException("mmr must be true, false or {lambda}");
        }
        if (m != Method.BM25 && !hasEmbedder) {
            throw new RagException("retrieval method '" + m.name().toLowerCase(Locale.ROOT) + "' needs an embedder; use bm25 or configure one");
        }
        Object expand = c.get("expand");
        if (expand != null && !"parent".equals(expand) && !"none".equals(expand)) {
            throw new RagException("expand must be 'parent' or 'none'");
        }
        return new RetrievalConfig(m, Chunkers.intOf(c, "topK", 5), lambda, "parent".equals(expand));
    }
}
