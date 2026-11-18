package tech.kayys.wayang.knowledge;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.connectors.MediaHttp;
import tech.kayys.wayang.rag.Embedder;
import tech.kayys.wayang.rag.RagException;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Semantic embeddings from an OpenAI-compatible {@code POST {baseUrl}/embeddings} endpoint (OpenAI, or a local server such as Ollama or
 * vLLM). Requests go through the SSRF guard, are batched (32 texts), time-limited and size-limited. Document text is sent to that endpoint,
 * so choose it as you would any data processor. The API key is only ever put in the Authorization header; errors never repeat it or the text.
 */
final class OpenAiEmbedder implements Embedder {

    static final int BATCH = 32;
    static final int MAX_INPUT_CHARS = 8000;

    private final MediaHttp http;
    private final String base;
    private final String model;
    private final int dimensions;
    private final Map<String, String> headers = new LinkedHashMap<>();

    OpenAiEmbedder(MediaHttp http, String base, String model, int dimensions, String apiKey) {
        if (!model.matches("[A-Za-z0-9._:/-]{1,100}")) {
            throw new RagException("embedder model is not a valid name");
        }
        if (dimensions < 8 || dimensions > 8192) {
            throw new RagException("embedder dimensions must be between 8 and 8192");
        }
        if (apiKey != null && !apiKey.isBlank()) {
            if (apiKey.length() > 4096 || apiKey.matches(".*\\s.*")) {
                throw new RagException("embedder apiKey is not valid (use ${secret:NAME})");
            }
            headers.put("Authorization", "Bearer " + apiKey);
        }
        this.http = http;
        this.base = base;
        this.model = model;
        this.dimensions = dimensions;
    }

    @Override
    public String id() {
        return "openai:" + model + ":" + dimensions;
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
        List<float[]> out = new ArrayList<>(texts.size());
        for (int i = 0; i < texts.size(); i += BATCH) {
            out.addAll(batch(texts.subList(i, Math.min(texts.size(), i + BATCH))));
        }
        return out;
    }

    private List<float[]> batch(List<String> texts) {
        List<String> input = texts.stream().map(t -> t.length() > MAX_INPUT_CHARS ? t.substring(0, MAX_INPUT_CHARS) : t).toList();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("input", input);
        body.put("encoding_format", "float");
        Object res;
        try {
            res = http.postJson(http.uri(base, "/embeddings"), headers, body, 60_000, 8 << 20);
        } catch (ServiceHandler.ConnectorException e) {
            throw new RagException("the embedding service failed: " + e.getMessage());
        }
        if (!(res instanceof Map<?, ?> m) || !(m.get("data") instanceof List<?> data) || data.size() != texts.size()) {
            throw new RagException("the embedding service returned an unexpected answer");
        }
        float[][] vectors = new float[texts.size()][];
        int pos = 0;
        for (Object o : data) {
            int at = o instanceof Map<?, ?> dm && dm.get("index") instanceof Number n ? n.intValue() : pos;
            pos++;
            if (!(o instanceof Map<?, ?> d) || !(d.get("embedding") instanceof List<?> e)
                    || at < 0 || at >= vectors.length || vectors[at] != null || e.size() != dimensions) {
                throw new RagException("the embedding service returned vectors that do not match dimensions=" + dimensions);
            }
            float[] v = new float[dimensions];
            for (int k = 0; k < dimensions; k++) {
                if (!(e.get(k) instanceof Number n)) {
                    throw new RagException("the embedding service returned a non-numeric value");
                }
                v[k] = n.floatValue();
            }
            vectors[at] = v;
        }
        return List.of(vectors);
    }
}
