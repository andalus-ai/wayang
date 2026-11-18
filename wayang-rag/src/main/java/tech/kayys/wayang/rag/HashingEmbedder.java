package tech.kayys.wayang.rag;

import java.util.List;

/**
 * Feature-hashing bag of words and word pairs, signed and L2-normalised. Deterministic, dependency-free and good for tests and offline use; it matches on shared
 * words, <em>not</em> on meaning (it will not link "car" and "automobile"). Use a real {@link Embedder} for semantic search.
 */
public final class HashingEmbedder implements Embedder {

    private final int dim;

    public HashingEmbedder(int dim) {
        if (dim < 16 || dim > 8192) {
            throw new RagException("dimensions must be between 16 and 8192");
        }
        this.dim = dim;
    }

    @Override
    public String id() {
        return "hashing-" + dim;
    }

    @Override
    public int dimensions() {
        return dim;
    }

    @Override
    public float[] embed(String text) {
        float[] v = new float[dim];
        List<String> t = Tokens.of(text);
        for (int i = 0; i < t.size(); i++) {
            add(v, t.get(i), 1f);
            if (i + 1 < t.size()) {
                add(v, t.get(i) + " " + t.get(i + 1), 0.5f);
            }
        }
        double norm = 0;
        for (float x : v) {
            norm += x * x;
        }
        if (norm > 0) {
            float inv = (float) (1.0 / Math.sqrt(norm));
            for (int i = 0; i < dim; i++) {
                v[i] *= inv;
            }
        }
        return v;
    }

    private void add(float[] v, String feature, float w) {
        long h = 0xcbf29ce484222325L;
        for (int i = 0; i < feature.length(); i++) {
            h ^= feature.charAt(i);
            h *= 0x100000001b3L;
        }
        int idx = (int) Long.remainderUnsigned(h, dim);
        v[idx] += ((h >>> 63) == 0 ? 1f : -1f) * w;
    }
}
