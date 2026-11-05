package tech.kayys.wayang.multimodal;

import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import tech.kayys.wayang.connectors.MediaHttp;
import tech.kayys.wayang.connectors.MediaStore;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code transcribe}: speech to text through an OpenAI-compatible {@code /audio/transcriptions} endpoint (OpenAI, or a local
 * Whisper server).
 *
 * <p>Config: {@code model} (default {@code whisper-1}), {@code apiKey} ({@code ${secret:NAME}}, optional for a local server), {@code baseUrl}
 * (default {@code https://api.openai.com/v1}), optional {@code language} (ISO 639-1 such as {@code id} or {@code en}). Input: {@code mediaId}
 * of stored audio. Output: {@code text} (at most 8000 characters). The transcript is what someone said: treat it as untrusted text.
 */
final class Transcribe implements ServiceHandler.Connector {

    static final int MAX_TEXT = 8000;

    private final MediaHttp http;
    private final MediaStore store;

    Transcribe(UrlGuard guard, MediaStore store) {
        this.http = new MediaHttp(guard);
        this.store = store;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("transcribe needs node configuration", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        String id = inputs.get("mediaId") instanceof String m ? m : null;
        byte[] audio = id == null ? null : store.read(id);
        String mime = audio == null ? null : MediaStore.sniffAudio(audio);
        if (mime == null) {
            throw new ServiceHandler.ConnectorException("transcribe needs the 'mediaId' of stored audio", false);
        }
        String model = config.get("model") instanceof String m && !m.isBlank() ? m : "whisper-1";
        if (!model.matches("[A-Za-z0-9._:/-]{1,100}")) {
            throw new ServiceHandler.ConnectorException("transcribe model is not a valid name", false);
        }
        String language = config.get("language") instanceof String l && !l.isBlank() ? l : null;
        if (language != null && !language.matches("[a-z]{2,3}(-[A-Za-z]{2,4})?")) {
            throw new ServiceHandler.ConnectorException("transcribe language must be an ISO 639-1 code such as id or en", false);
        }
        String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b : "https://api.openai.com/v1";
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.get("apiKey") instanceof String k && !k.isBlank()) {
            if (k.length() > 4096 || k.matches(".*\\s.*")) {
                throw new ServiceHandler.ConnectorException("apiKey is not valid (use ${secret:NAME})", false);
            }
            headers.put("Authorization", "Bearer " + k);
        }
        String boundary = "wayang" + UUID.randomUUID().toString().replace("-", "");
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        field(out, boundary, "model", model);
        field(out, boundary, "response_format", "json");
        if (language != null) {
            field(out, boundary, "language", language);
        }
        String ext = id.substring(id.lastIndexOf('.') + 1);
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"audio." + ext + "\"\r\nContent-Type: " + mime
                + "\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        out.write(audio);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        Object res = http.postBytes(http.uri(base, "/audio/transcriptions"), headers, "multipart/form-data; boundary=" + boundary, out.toByteArray(),
                120_000, 1 << 20);
        if (res instanceof Map<?, ?> m && m.get("text") instanceof String t) {
            String text = t.strip();
            return Map.of("text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
        }
        throw new ServiceHandler.ConnectorException("The provider returned no transcript", false);
    }

    private static void field(ByteArrayOutputStream out, String boundary, String name, String value) throws java.io.IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n").getBytes(StandardCharsets.UTF_8));
    }
}
