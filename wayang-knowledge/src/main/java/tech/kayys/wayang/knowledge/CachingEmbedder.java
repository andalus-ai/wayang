package tech.kayys.wayang.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.rag.Embedder;
import tech.kayys.wayang.rag.RagException;

/** An {@link Embedder} that asks the real one only for document texts it has not embedded before (see {@link VectorCache}); same vectors, fewer calls. */
final class CachingEmbedder implements Embedder {

    private final Embedder inner;
    private final VectorCache cache;

    CachingEmbedder(Embedder inner, VectorCache cache) {
        this.inner = inner;
        this.cache = cache;
    }

    @Override
    public String id() {
        return inner.id();
    }

    @Override
    public int dimensions() {
        return inner.dimensions();
    }

    /** A single text is a search question: it may be answered from the cache but is never stored, so questions cannot crowd out document vectors. */
    @Override
    public float[] embed(String text) {
        float[] v = cache.get(text);
        return v != null ? v : inner.embed(text);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        float[][] out = new float[texts.size()][];
        Map<String, List<Integer>> misses = new LinkedHashMap<>();
        for (int i = 0; i < texts.size(); i++) {
            float[] v = cache.get(texts.get(i));
            if (v != null) {
                out[i] = v;
            } else {
                misses.computeIfAbsent(texts.get(i), k -> new ArrayList<>()).add(i);
            }
        }
        if (!misses.isEmpty()) {
            List<String> todo = new ArrayList<>(misses.keySet());
            List<float[]> got = inner.embedAll(todo);
            if (got == null || got.size() != todo.size()) {
                throw new RagException("the embedder returned the wrong number of vectors");
            }
            Map<String, float[]> fresh = new LinkedHashMap<>();
            for (int j = 0; j < todo.size(); j++) {
                float[] v = got.get(j);
                if (v == null || v.length != dimensions()) {
                    throw new RagException("the embedder returned a vector of the wrong size");
                }
                fresh.put(todo.get(j), v);
                for (int i : misses.get(todo.get(j))) {
                    out[i] = v;
                }
            }
            cache.put(fresh);
        }
        return List.of(out);
    }
}
