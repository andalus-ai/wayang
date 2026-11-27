package tech.kayys.wayang.adapter.aqli;

import java.util.List;

import tech.kayys.wayang.rag.Embedder;

/** AQLI's documented /v1/embeddings endpoint exposed through Wayang's provider-neutral Embedder port. */
public final class AqliEmbedder implements Embedder {
    private final AqliInferenceClient client;
    private final String model;
    private final int dimensions;

    public AqliEmbedder(AqliInferenceClient client, String model, int dimensions) {
        if (client == null || model == null || model.isBlank()) {
            throw new IllegalArgumentException("AQLI client and embedding model are required");
        }
        if (dimensions < 1 || dimensions > 65_536) {
            throw new IllegalArgumentException("embedding dimensions must be between 1 and 65536");
        }
        this.client = client;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public String id() {
        return "aqli:" + model + ":" + dimensions;
    }

    @Override
    public int dimensions() {
        return dimensions;
    }

    @Override
    public float[] embed(String text) {
        return embedAll(List.of(text)).get(0);
    }

    @Override
    public List<float[]> embedAll(List<String> texts) {
        List<float[]> vectors = client.embeddings(model, texts);
        for (float[] vector : vectors) {
            if (vector.length != dimensions) {
                throw new IllegalStateException("AQLI embedding dimension did not match configured dimension "
                        + dimensions + "; got " + vector.length);
            }
        }
        return vectors;
    }
}
