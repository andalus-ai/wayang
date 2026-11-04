package tech.kayys.wayang.multimodal;

import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.connectors.MediaStore;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code image-generate}: a text-to-image call to an OpenAI-compatible {@code /images/generations} endpoint.
 *
 * <p>Config: {@code apiKey} (as {@code ${secret:NAME}}; optional for a local server), {@code model}, optional {@code baseUrl} (default
 * {@code https://api.openai.com/v1}), {@code size} (default 1024x1024; letters, digits, x). Input: {@code prompt} (at most 4000 characters).
 * Output: {@code mediaId}, {@code mimeType}, {@code bytes}: the image is stored in the {@link MediaStore}, never put in the run.
 */
final class ImageGenerate implements ServiceHandler.Connector {

    private final MediaHttp http;
    private final MediaStore store;

    ImageGenerate(UrlGuard guard, MediaStore store) {
        this.http = new MediaHttp(guard);
        this.store = store;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("image-generate needs node configuration", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        String prompt = inputs.get("prompt") instanceof String p ? p : null;
        if (prompt == null || prompt.isBlank() || prompt.length() > 4000) {
            throw new ServiceHandler.ConnectorException("image-generate needs a 'prompt' of 1..4000 characters", false);
        }
        String model = config.get("model") instanceof String m && m.matches("[A-Za-z0-9._:/-]{1,100}") ? m : null;
        if (model == null) {
            throw new ServiceHandler.ConnectorException("image-generate needs a 'model'", false);
        }
        String size = config.get("size") instanceof String s ? s : "1024x1024";
        if (!size.matches("[0-9]{2,5}x[0-9]{2,5}")) {
            throw new ServiceHandler.ConnectorException("size must look like 1024x1024", false);
        }
        String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b : "https://api.openai.com/v1";
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.get("apiKey") instanceof String k && !k.isBlank()) {
            if (k.length() > 4096 || k.matches(".*\\s.*")) {
                throw new ServiceHandler.ConnectorException("apiKey is not valid (use ${secret:NAME})", false);
            }
            headers.put("Authorization", "Bearer " + k);
        }
        Object res = http.postJson(http.uri(base, "/images/generations"), headers,
                Map.of("model", model, "prompt", prompt, "n", 1, "size", size, "response_format", "b64_json"), 120_000, 40L << 20);
        if (res instanceof Map<?, ?> m && m.get("data") instanceof List<?> d && !d.isEmpty() && d.get(0) instanceof Map<?, ?> first
                && first.get("b64_json") instanceof String b64) {
            byte[] bytes;
            try {
                bytes = Base64.getMimeDecoder().decode(b64);
            } catch (IllegalArgumentException e) {
                throw new ServiceHandler.ConnectorException("The provider returned an image that is not valid base64", false);
            }
            return store.save(bytes);
        }
        throw new ServiceHandler.ConnectorException("The provider returned no image", false);
    }
}
