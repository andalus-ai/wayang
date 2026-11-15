package tech.kayys.wayang.management.client;

import static tech.kayys.wayang.management.client.ManagementContracts.*;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

/**
 * Synchronous, transport-only client for the Console's implemented REST/JSON management API v1.
 *
 * <p>Enrollment deliberately has no bearer header: its one-time enrollment credential is sent
 * only in the request body. All post-enrollment operations use the returned registration bearer
 * token. This class does not persist credentials, retry non-idempotent enrollment, or execute
 * deployment commands; those policies belong to the runtime host.</p>
 */
public final class ManagementClient {
    private static final String API_PREFIX = "api/v1/runtime/";

    private final URI baseUri;
    private final Duration requestTimeout;
    private final HttpClient http;
    private final ObjectMapper json;

    public ManagementClient(URI baseUri, Duration connectTimeout, Duration requestTimeout) {
        this.baseUri = validateBaseUri(baseUri);
        this.requestTimeout = positive(requestTimeout, "requestTimeout");
        this.http = HttpClient.newBuilder().connectTimeout(positive(connectTimeout, "connectTimeout")).build();
        this.json = new ObjectMapper().findAndRegisterModules();
    }

    ManagementClient(URI baseUri, Duration requestTimeout, HttpClient http, ObjectMapper json) {
        this.baseUri = validateBaseUri(baseUri);
        this.requestTimeout = positive(requestTimeout, "requestTimeout");
        this.http = Objects.requireNonNull(http, "http");
        this.json = Objects.requireNonNull(json, "json");
    }

    public EnrollResponse enroll(EnrollRequest request) throws IOException {
        Objects.requireNonNull(request, "request");
        return post("enroll", null, request, EnrollResponse.class);
    }

    public HeartbeatResponse heartbeat(String registrationToken, String health) throws IOException {
        return post("heartbeat", requiredBearer(registrationToken), new HeartbeatRequest(health), HeartbeatResponse.class);
    }

    public InventoryResponse reportInventory(String registrationToken, InventoryRequest inventory) throws IOException {
        Objects.requireNonNull(inventory, "inventory");
        return post("inventory", requiredBearer(registrationToken), inventory, InventoryResponse.class);
    }

    public CommandPage pollCommands(String registrationToken, int limit) throws IOException {
        if (limit < 1 || limit > 50) throw new IllegalArgumentException("limit must be between 1 and 50");
        return post("commands/poll", requiredBearer(registrationToken), new PollRequest(limit), CommandPage.class);
    }

    public AckResponse acknowledge(String registrationToken, UUID commandId, AckRequest acknowledgement)
            throws IOException {
        Objects.requireNonNull(commandId, "commandId");
        Objects.requireNonNull(acknowledgement, "acknowledgement");
        return post("commands/" + commandId + "/ack", requiredBearer(registrationToken), acknowledgement, AckResponse.class);
    }

    private static String requiredBearer(String token) {
        if (token == null || token.isBlank()) throw new IllegalArgumentException("registrationToken must not be blank");
        return token;
    }

    private <T> T post(String path, String bearer, Object body, Class<T> responseType) throws IOException {
        HttpRequest.Builder request = HttpRequest.newBuilder(endpoint(path))
                .timeout(requestTimeout)
                .header("Accept", "application/json")
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(write(body)));
        if (bearer != null) {
            if (bearer.isBlank()) throw new IllegalArgumentException("registrationToken must not be blank");
            request.header("Authorization", "Bearer " + bearer);
        }
        final HttpResponse<String> response;
        try {
            response = http.send(request.build(), HttpResponse.BodyHandlers.ofString());
        } catch (InterruptedException interrupted) {
            Thread.currentThread().interrupt();
            throw new IOException("Interrupted while calling Wayang Console management API", interrupted);
        }
        if (response.statusCode() < 200 || response.statusCode() >= 300) {
            ErrorResponse error = readError(response.body());
            throw new ManagementApiException(response.statusCode(), error.code(), error.message());
        }
        try {
            return json.readValue(response.body(), responseType);
        } catch (JsonProcessingException malformed) {
            throw new IOException("Console management API returned an invalid " + responseType.getSimpleName(), malformed);
        }
    }

    private String write(Object body) throws IOException {
        try {
            return json.writeValueAsString(body);
        } catch (JsonProcessingException malformed) {
            throw new IOException("Unable to serialize Console management request", malformed);
        }
    }

    private ErrorResponse readError(String body) {
        try {
            ErrorResponse parsed = json.readValue(body, ErrorResponse.class);
            return parsed == null ? new ErrorResponse("http_error", "Management API request failed") : parsed;
        } catch (Exception ignored) {
            return new ErrorResponse("http_error", "Management API request failed with HTTP error");
        }
    }

    private URI endpoint(String path) {
        return baseUri.resolve(API_PREFIX + path);
    }

    private static URI validateBaseUri(URI uri) {
        Objects.requireNonNull(uri, "baseUri");
        String scheme = uri.getScheme();
        if (!uri.isAbsolute() || !("http".equalsIgnoreCase(scheme) || "https".equalsIgnoreCase(scheme))
                || uri.getHost() == null || uri.getUserInfo() != null || uri.getQuery() != null
                || uri.getFragment() != null) {
            throw new IllegalArgumentException("baseUri must be an absolute HTTP(S) URI without user-info, query or fragment");
        }
        String normalized = uri.toString();
        return URI.create(normalized.endsWith("/") ? normalized : normalized + "/");
    }

    private static Duration positive(Duration value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isZero() || value.isNegative()) throw new IllegalArgumentException(name + " must be positive");
        return value;
    }
}
