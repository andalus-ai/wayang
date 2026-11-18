package tech.kayys.wayang.rag;

/**
 * Port to whatever turns text into a vector (a model endpoint, Andalus's embedder, a local model). Wayang ships only {@link HashingEmbedder}, a dependency-free
 * lexical baseline; real semantic retrieval needs a real embedder behind this port. Implementations must return vectors of one fixed dimension.
 */
public interface Embedder {

    /** Stable identifier of the model/space; vectors from different ids must never be mixed in one index. */
    String id();

    int dimensions();

    /** @return a vector of {@link #dimensions()} values; it need not be normalised */
    float[] embed(String text);
}
