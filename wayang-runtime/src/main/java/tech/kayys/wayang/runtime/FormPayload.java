package tech.kayys.wayang.runtime;

import java.net.URLDecoder;
import java.nio.charset.StandardCharsets;
import java.util.Map;
import java.util.Optional;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Slack's interactivity requests are not JSON: they are {@code application/x-www-form-urlencoded} with one field, {@code payload}, that holds
 * the JSON. The signature is computed over the raw body either way; this only reads it after it has been verified.
 */
public final class FormPayload {

    private FormPayload() {
    }

    /** The JSON object in the form's {@code payload} field, or empty when the request is not a form with one. */
    public static Optional<Map<String, Object>> parse(String contentType, byte[] raw) {
        if (contentType == null || !contentType.toLowerCase(java.util.Locale.ROOT).startsWith("application/x-www-form-urlencoded")) {
            return Optional.empty();
        }
        for (String pair : new String(raw, StandardCharsets.UTF_8).split("&")) {
            if (pair.startsWith("payload=")) {
                try {
                    Object v = Json.read(URLDecoder.decode(pair.substring("payload=".length()), StandardCharsets.UTF_8));
                    if (v instanceof Map<?, ?> m) {
                        @SuppressWarnings("unchecked")
                        Map<String, Object> typed = (Map<String, Object>) m;
                        return Optional.of(typed);
                    }
                } catch (RuntimeException e) {
                    // not JSON: handled as a bad request by the caller
                }
                throw new IllegalArgumentException("payload is not a JSON object");
            }
        }
        return Optional.empty();
    }
}
