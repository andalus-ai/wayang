package tech.kayys.wayang.adapter.aqli;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Small Java 21 HTTP client for AQLI's documented OpenAI-compatible endpoints.
 *
 * <p>This adapter intentionally targets AQLI's service API instead of its Java 25 SDK,
 * allowing Java 21 Wayang deployments to integrate without raising their runtime baseline.
 * It does not retry inference POSTs: duplicate generation is expensive and provider-side
 * idempotency is not part of the documented contract.
 */
public final class AqliInferenceClient {
    static final int DEFAULT_MAX_RESPONSE_BYTES = 4 * 1024 * 1024;
    private static final ObjectMapper JSON = new ObjectMapper();

    @FunctionalInterface
    public interface Transport {
        Response post(URI uri, Map<String, String> headers, String body, Duration timeout);
    }

    public record Response(int status, String body) {
    }

    private final URI baseUri;
    private final String apiKey;
    private final Duration timeout;
    private final int maxResponseBytes;
    private final Transport transport;

    public AqliInferenceClient(URI baseUri, String apiKey, Duration timeout) {
        this(baseUri, apiKey, timeout, DEFAULT_MAX_RESPONSE_BYTES, jdkTransport(timeout,
                DEFAULT_MAX_RESPONSE_BYTES));
    }

    public AqliInferenceClient(URI baseUri, String apiKey, Duration timeout,
            int maxResponseBytes, Transport transport) {
        if (baseUri == null || !("http".equalsIgnoreCase(baseUri.getScheme())
                || "https".equalsIgnoreCase(baseUri.getScheme()))
                || baseUri.getHost() == null || baseUri.getUserInfo() != null
                || baseUri.getQuery() != null || baseUri.getFragment() != null) {
            throw new IllegalArgumentException("AQLI base URI must be an absolute HTTP(S) URI without credentials, query or fragment");
        }
        if (apiKey == null || apiKey.isBlank()) {
            throw new IllegalArgumentException("AQLI API key is required");
        }
        if (timeout == null || timeout.isZero() || timeout.isNegative()) {
            throw new IllegalArgumentException("AQLI request timeout must be positive");
        }
        if (maxResponseBytes < 1 || maxResponseBytes > 32 * 1024 * 1024) {
            throw new IllegalArgumentException("AQLI max response size must be between 1 byte and 32 MiB");
        }
        if (transport == null) {
            throw new IllegalArgumentException("AQLI transport is required");
        }
        this.baseUri = URI.create(baseUri.toString().replaceAll("/+$", ""));
        this.apiKey = apiKey;
        this.timeout = timeout;
        this.maxResponseBytes = maxResponseBytes;
        this.transport = transport;
    }

    public String chatCompletion(String model, String systemPrompt, String userPrompt, int maxTokens) {
        requireText(model, "model");
        requireText(userPrompt, "user prompt");
        if (maxTokens < 1 || maxTokens > 4096) {
            throw new IllegalArgumentException("AQLI max tokens must be between 1 and 4096");
        }
        List<Map<String, String>> messages = systemPrompt == null || systemPrompt.isBlank()
                ? List.of(Map.of("role", "user", "content", userPrompt))
                : List.of(Map.of("role", "system", "content", systemPrompt),
                        Map.of("role", "user", "content", userPrompt));
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("model", model);
        payload.put("messages", messages);
        payload.put("max_tokens", maxTokens);
        payload.put("temperature", 0.0);
        JsonNode root = post("/v1/chat/completions", payload);
        JsonNode content = root.path("choices").path(0).path("message").path("content");
        if (!content.isTextual()) {
            throw new IllegalStateException("AQLI chat response did not contain textual assistant content");
        }
        return content.textValue();
    }

    public List<float[]> embeddings(String model, List<String> inputs) {
        requireText(model, "embedding model");
        if (inputs == null || inputs.isEmpty() || inputs.size() > 256
                || inputs.stream().anyMatch(value -> value == null || value.isBlank() || value.length() > 100_000)) {
            throw new IllegalArgumentException("AQLI embedding input must contain 1..256 non-empty texts of at most 100000 characters");
        }
        JsonNode root = post("/v1/embeddings", Map.of("model", model, "input", inputs));
        JsonNode data = root.path("data");
        if (!data.isArray() || data.size() != inputs.size()) {
            throw new IllegalStateException("AQLI embedding response count did not match the request");
        }
        java.util.ArrayList<float[]> vectors = new java.util.ArrayList<>(data.size());
        for (JsonNode item : data) {
            JsonNode vector = item.path("embedding");
            if (!vector.isArray() || vector.isEmpty()) {
                throw new IllegalStateException("AQLI returned an empty or invalid embedding");
            }
            float[] values = new float[vector.size()];
            for (int i = 0; i < vector.size(); i++) {
                JsonNode value = vector.get(i);
                if (!value.isNumber() || !Double.isFinite(value.doubleValue())
                        || !Float.isFinite(value.floatValue())) {
                    throw new IllegalStateException("AQLI embedding contained a non-finite or non-numeric value");
                }
                values[i] = value.floatValue();
            }
            vectors.add(values);
        }
        return List.copyOf(vectors);
    }

    private JsonNode post(String path, Object payload) {
        final String body;
        try {
            body = JSON.writeValueAsString(payload);
        } catch (IOException e) {
            throw new IllegalStateException("Could not encode AQLI request", e);
        }
        Map<String, String> headers = Map.of(
                "X-API-Key", apiKey,
                "Accept", "application/json",
                "Content-Type", "application/json");
        Response response = transport.post(URI.create(baseUri + path), headers, body, timeout);
        if (response == null || response.body() == null) {
            throw new IllegalStateException("AQLI returned an empty HTTP response");
        }
        if (response.body().getBytes(StandardCharsets.UTF_8).length > maxResponseBytes) {
            throw new IllegalStateException("AQLI response exceeded the configured size limit");
        }
        if (response.status() < 200 || response.status() >= 300) {
            throw new IllegalStateException("AQLI inference request failed with HTTP status " + response.status());
        }
        try {
            return JSON.readTree(response.body());
        } catch (IOException e) {
            throw new IllegalStateException("AQLI returned malformed JSON", e);
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank() || value.length() > 500) {
            throw new IllegalArgumentException("AQLI " + field + " must contain 1..500 characters");
        }
    }

    private static Transport jdkTransport(Duration connectTimeout, int maxBytes) {
        HttpClient client = HttpClient.newBuilder().connectTimeout(connectTimeout).build();
        return (uri, headers, body, timeout) -> {
            HttpRequest.Builder request = HttpRequest.newBuilder(uri).timeout(timeout)
                    .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8));
            headers.forEach(request::header);
            try {
                HttpResponse<InputStream> response = client.send(request.build(),
                        HttpResponse.BodyHandlers.ofInputStream());
                try (InputStream stream = response.body()) {
                    byte[] bytes = stream.readNBytes(maxBytes + 1);
                    if (bytes.length > maxBytes) {
                        throw new IllegalStateException("AQLI response exceeded the configured size limit");
                    }
                    return new Response(response.statusCode(), new String(bytes, StandardCharsets.UTF_8));
                }
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException("AQLI inference request was interrupted", e);
            } catch (IOException e) {
                throw new IllegalStateException("AQLI inference transport failed", e);
            }
        };
    }
}
