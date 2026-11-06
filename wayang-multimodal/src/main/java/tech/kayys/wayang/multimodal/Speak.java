package tech.kayys.wayang.multimodal;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.kayys.wayang.connectors.MediaHttp;
import tech.kayys.wayang.connectors.MediaStore;
import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code speak}: text to speech through an OpenAI-compatible {@code /audio/speech} endpoint (OpenAI, or a local server). The audio
 * is stored in the media store and returned as {@code mediaId}, ready for the telegram {@code sendVoice} operation.
 *
 * <p>Config: {@code model} (default {@code tts-1}), {@code voice} (default {@code alloy}), {@code format} ({@code opus} default, which is
 * what Telegram shows as a voice note; or {@code mp3}, {@code wav}), {@code apiKey} ({@code ${secret:NAME}}, optional for a local server),
 * {@code baseUrl}. Input: {@code text} (at most 4000 characters).
 */
final class Speak implements ServiceHandler.Connector {

    static final int MAX_TEXT = 4000;

    private final MediaHttp http;
    private final MediaStore store;

    Speak(UrlGuard guard, MediaStore store) {
        this.http = new MediaHttp(guard);
        this.store = store;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("speak needs node configuration", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        String text = inputs.get("text") instanceof String t ? t.strip() : "";
        if (text.isEmpty()) {
            throw new ServiceHandler.ConnectorException("speak needs non-empty 'text'", false);
        }
        if (text.codePointCount(0, text.length()) > MAX_TEXT) {
            throw new ServiceHandler.ConnectorException("Text longer than " + MAX_TEXT + " characters", false);
        }
        String model = name(config.get("model"), "tts-1", "model");
        String voice = name(config.get("voice"), "alloy", "voice");
        String format = config.get("format") instanceof String f && !f.isBlank() ? f : "opus";
        if (!format.equals("opus") && !format.equals("mp3") && !format.equals("wav")) {
            throw new ServiceHandler.ConnectorException("speak format must be opus, mp3 or wav", false);
        }
        String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b : "https://api.openai.com/v1";
        Map<String, String> headers = new LinkedHashMap<>();
        if (config.get("apiKey") instanceof String k && !k.isBlank()) {
            if (k.length() > 4096 || k.matches(".*\\s.*")) {
                throw new ServiceHandler.ConnectorException("apiKey is not valid (use ${secret:NAME})", false);
            }
            headers.put("Authorization", "Bearer " + k);
        }
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("model", model);
        body.put("voice", voice);
        body.put("input", text);
        body.put("response_format", format);
        byte[] audio = http.postJsonForBytes(http.uri(base, "/audio/speech"), headers, body, 120_000, 10L << 20);
        return store.saveAudio(audio);
    }

    private static String name(Object v, String dflt, String what) throws ServiceHandler.ConnectorException {
        String s = v instanceof String x && !x.isBlank() ? x : dflt;
        if (!s.matches("[A-Za-z0-9._:/-]{1,100}")) {
            throw new ServiceHandler.ConnectorException("speak " + what + " is not a valid name", false);
        }
        return s;
    }
}
