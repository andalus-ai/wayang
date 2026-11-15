package tech.kayys.wayang.management.client;

import static org.junit.jupiter.api.Assertions.*;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import java.io.IOException;
import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.wayang.management.client.ManagementContracts.*;

class ManagementClientTest {
    private static final ObjectMapper JSON = new ObjectMapper().findAndRegisterModules();
    private HttpServer server;
    private ManagementClient client;
    private final AtomicReference<String> enrollmentAuthorization = new AtomicReference<>();
    private final AtomicReference<String> bearerAuthorization = new AtomicReference<>();
    private final AtomicReference<JsonNode> lastRequest = new AtomicReference<>();

    @BeforeEach
    void startServer() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/api/v1/runtime/", this::handle);
        server.start();
        client = new ManagementClient(URI.create("http://127.0.0.1:" + server.getAddress().getPort()),
                Duration.ofSeconds(2), Duration.ofSeconds(3));
    }

    @AfterEach
    void stopServer() {
        if (server != null) server.stop(0);
    }

    @Test
    void implementsAllFiveConsoleManagementOperationsAndCredentialBoundary() throws Exception {
        EnrollResponse enrolled = client.enroll(new EnrollRequest(
                "one-time-enrollment-secret", "runtime-01", "0.0.1", "deployment-01", "host-a", "linux-amd64"));
        assertEquals("registration-01", enrolled.registrationId());
        assertEquals("registration-secret", enrolled.registrationToken());
        assertNull(enrollmentAuthorization.get(), "enrollment credential is sent in the body, not Authorization");
        assertEquals("one-time-enrollment-secret", lastRequest.get().path("enrollmentToken").asText());

        HeartbeatResponse heartbeat = client.heartbeat(enrolled.registrationToken(), "HEALTHY");
        assertEquals(30, heartbeat.heartbeatIntervalSeconds());
        assertEquals("Bearer registration-secret", bearerAuthorization.get());
        assertEquals("HEALTHY", lastRequest.get().path("health").asText());

        InventoryRequest inventory = new InventoryRequest(
                List.of(new CapabilityInput("wayang.agent.invoke", "0.0.1", "agent.invoke")),
                List.of(new WorkflowInput("orders", "1", "sha256:" + "a".repeat(64), "orders-prod")));
        InventoryResponse reported = client.reportInventory(enrolled.registrationToken(), inventory);
        assertEquals("revision-01", reported.revision());
        assertEquals(1, lastRequest.get().path("capabilities").size());
        assertEquals("orders", lastRequest.get().path("workflows").get(0).path("workflowId").asText());

        CommandPage page = client.pollCommands(enrolled.registrationToken(), 12);
        assertEquals(1, page.commands().size());
        assertEquals(3, page.commands().get(0).attemptCount());
        assertEquals(12, lastRequest.get().path("limit").asInt());

        UUID commandId = page.commands().get(0).commandId();
        AckResponse ack = client.acknowledge(enrolled.registrationToken(), commandId,
                new AckRequest("SUCCEEDED", "deployment applied", "sha256:" + "b".repeat(64)));
        assertTrue(ack.accepted());
        assertEquals(commandId, ack.commandId());
        assertEquals("SUCCEEDED", lastRequest.get().path("status").asText());
        assertEquals("sha256:" + "b".repeat(64), lastRequest.get().path("verifiedArtifactDigest").asText());
    }

    @Test
    void rejectsInvalidPollLimitsAndClassifiesCredentialErrors() throws Exception {
        assertThrows(IllegalArgumentException.class, () -> client.pollCommands("token", 0));
        assertThrows(IllegalArgumentException.class, () -> client.pollCommands("token", 51));

        server.removeContext("/api/v1/runtime/");
        server.createContext("/api/v1/runtime/", exchange -> respond(exchange, 401,
                "{\"code\":\"unauthorized\",\"message\":\"Runtime credential is invalid or expired\"}"));
        ManagementApiException failure = assertThrows(ManagementApiException.class,
                () -> client.heartbeat("expired-token", "HEALTHY"));
        assertEquals(401, failure.statusCode());
        assertEquals("unauthorized", failure.errorCode());
        assertTrue(failure.credentialRejected());
        assertFalse(failure.retryable());
    }

    @Test
    void rejectsUnsafeBaseUris() {
        assertThrows(IllegalArgumentException.class, () -> new ManagementClient(
                URI.create("https://user:secret@example.test"), Duration.ofSeconds(1), Duration.ofSeconds(1)));
        assertThrows(IllegalArgumentException.class, () -> new ManagementClient(
                URI.create("file:///tmp/management"), Duration.ofSeconds(1), Duration.ofSeconds(1)));
    }

    private void handle(HttpExchange exchange) throws IOException {
        String path = exchange.getRequestURI().getPath();
        String body = new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8);
        lastRequest.set(body.isBlank() ? JSON.createObjectNode() : JSON.readTree(body));
        String authorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (path.endsWith("/enroll")) enrollmentAuthorization.set(authorization);
        else bearerAuthorization.set(authorization);

        if (path.endsWith("/enroll")) {
            respond(exchange, 200, """
                    {"registrationId":"registration-01","registrationToken":"registration-secret",
                     "registrationExpiresAt":"2030-01-01T00:00:00Z","leaseExpiresAt":"2030-01-01T00:02:00Z",
                     "heartbeatIntervalSeconds":30}
                    """);
        } else if (path.endsWith("/heartbeat")) {
            respond(exchange, 200, "{\"leaseExpiresAt\":\"2030-01-01T00:02:00Z\",\"heartbeatIntervalSeconds\":30}");
        } else if (path.endsWith("/inventory")) {
            respond(exchange, 200, "{\"revision\":\"revision-01\",\"capabilityCount\":1,\"workflowCount\":1,\"reportedAt\":\"2030-01-01T00:00:00Z\"}");
        } else if (path.endsWith("/commands/poll")) {
            respond(exchange, 200, """
                    {"commands":[{"commandId":"4f0c9b3a-57c2-45a4-a0f2-9b66e8c46c5a",
                    "idempotencyKey":"deploy:1","commandType":"APPLY_DEPLOYMENT","typedPayload":"{}",
                    "expiresAt":"2030-01-01T00:30:00Z","attemptCount":3}]}
                    """);
        } else if (path.endsWith("/ack")) {
            String commandId = path.substring(path.lastIndexOf('/') + 1, path.length() - "/ack".length());
            respond(exchange, 200, "{\"commandId\":\"" + commandId
                    + "\",\"status\":\"SUCCEEDED\",\"accepted\":true,\"completedAt\":\"2030-01-01T00:00:00Z\","
                    + "\"verifiedArtifactDigest\":\"sha256:" + "b".repeat(64) + "\"}");
        } else {
            respond(exchange, 404, "{\"code\":\"not_found\",\"message\":\"not found\"}");
        }
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (var output = exchange.getResponseBody()) {
            output.write(bytes);
        }
    }
}
