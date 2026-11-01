package tech.kayys.wayang.security.secrets.test;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import java.time.Duration;
import java.util.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.CoreMatchers.*;

import static org.junit.jupiter.api.Assertions.*;

import tech.kayys.wayang.security.secrets.core.SecretManager;
import tech.kayys.wayang.security.secrets.dto.*;
import tech.kayys.wayang.security.secrets.exception.SecretException;
import tech.kayys.wayang.security.secrets.core.DefaultSecretManager;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.DisplayName;

/**
 * Comprehensive test suite for secret management system.
 * 
 * Test Coverage:
 * - Secret storage and retrieval
 * - Secret rotation
 * - Expiration handling
 * - Caching
 * - REST API endpoints
 * - Performance benchmarks
 */
@QuarkusTest
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SecretManagementIntegrationTest {

    /*
     * @Container
     * static PostgreSQLContainer<?> postgres = new
     * PostgreSQLContainer<>("postgres:15-alpine")
     * .withDatabaseName("wayang_test")
     * .withUsername("test")
     * .withPassword("test");
     */

    @Inject
    @DefaultSecretManager
    SecretManager secretManager;

    private static final String TEST_TENANT = "test-tenant";
    private static final String TEST_SECRET_PATH = "test/api-key";

    @BeforeEach
    void setup() {
    }

    @AfterEach
    void cleanup() {
        // Cleanup test data
    }

    // =========================================================================
    // Secret Manager Tests
    // =========================================================================

    @Test
    @Order(1)
    @DisplayName("Store and retrieve secret")
    void testStoreAndRetrieveSecret() {
        StoreSecretRequest request = StoreSecretRequest.builder()
                .tenantId(TEST_TENANT)
                .path(TEST_SECRET_PATH)
                .data(Map.of("api_key", "test_key_123"))
                .type(SecretType.API_KEY)
                .rotatable(true)
                .build();

        SecretMetadata metadata = secretManager.store(request)
                .await().indefinitely();

        assertNotNull(metadata);
        assertEquals(TEST_TENANT, metadata.tenantId());
        assertEquals(TEST_SECRET_PATH, metadata.path());
        assertEquals(1, metadata.version());

        Secret secret = secretManager.retrieve(
                RetrieveSecretRequest.latest(TEST_TENANT, TEST_SECRET_PATH)).await().indefinitely();

        assertNotNull(secret);
        assertEquals("test_key_123", secret.data().get("api_key"));
    }

    @Test
    @Order(2)
    @DisplayName("Secret rotation")
    void testSecretRotation() {
        StoreSecretRequest request = StoreSecretRequest.builder()
                .tenantId(TEST_TENANT)
                .path("rotation-test")
                .data(Map.of("password", "old_password"))
                .type(SecretType.DATABASE_CREDENTIAL)
                .rotatable(true)
                .build();

        secretManager.store(request).await().indefinitely();

        RotateSecretRequest rotateRequest = RotateSecretRequest.deprecateOld(
                TEST_TENANT,
                "rotation-test",
                Map.of("password", "new_password"));

        SecretMetadata newMetadata = secretManager.rotate(rotateRequest)
                .await().indefinitely();

        assertEquals(2, newMetadata.version());

        Secret secret = secretManager.retrieve(
                RetrieveSecretRequest.latest(TEST_TENANT, "rotation-test")).await().indefinitely();

        assertEquals("new_password", secret.data().get("password"));
    }

    @Test
    @Order(3)
    @DisplayName("Secret expiration")
    void testSecretExpiration() {
        StoreSecretRequest request = StoreSecretRequest.builder()
                .tenantId(TEST_TENANT)
                .path("expiring-secret")
                .data(Map.of("key", "value"))
                .type(SecretType.GENERIC)
                .ttl(Duration.ofSeconds(2))
                .build();

        secretManager.store(request).await().indefinitely();

        assertTrue(secretManager.exists(TEST_TENANT, "expiring-secret")
                .await().indefinitely());

        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }

        assertThrows(SecretException.class, () -> secretManager.retrieve(
                RetrieveSecretRequest.latest(TEST_TENANT, "expiring-secret")).await().indefinitely());
    }

    @Test
    @Order(4)
    @DisplayName("Secret caching")
    void testSecretCaching() {
        String path = "cached-secret";

        StoreSecretRequest request = StoreSecretRequest.builder()
                .tenantId(TEST_TENANT)
                .path(path)
                .data(Map.of("value", "cached"))
                .type(SecretType.GENERIC)
                .build();

        secretManager.store(request).await().indefinitely();

        long start1 = System.currentTimeMillis();
        secretManager.retrieve(RetrieveSecretRequest.latest(TEST_TENANT, path))
                .await().indefinitely();
        long time1 = System.currentTimeMillis() - start1;

        long start2 = System.currentTimeMillis();
        secretManager.retrieve(RetrieveSecretRequest.latest(TEST_TENANT, path))
                .await().indefinitely();
        long time2 = System.currentTimeMillis() - start2;

        assertTrue(time2 < time1,
                "Cached retrieval should be faster: " + time2 + "ms vs " + time1 + "ms");
    }

    // =========================================================================
    // REST API Tests
    // =========================================================================

    @Test
    @Order(20)
    @DisplayName("REST API: Store secret")
    void testRestAPIStoreSecret() {
        given()
                .contentType(ContentType.JSON)
                .body(Map.of(
                        "tenantId", TEST_TENANT,
                        "path", "rest-test/secret",
                        "data", Map.of("key", "value"),
                        "type", "GENERIC"))
                .when()
                .post("/api/v1/secrets")
                .then()
                .statusCode(201)
                .body("tenantId", equalTo(TEST_TENANT))
                .body("path", equalTo("rest-test/secret"));
    }

    @Test
    @Order(21)
    @DisplayName("REST API: Retrieve secret")
    void testRestAPIRetrieveSecret() {
        testRestAPIStoreSecret();

        given()
                .queryParam("tenantId", TEST_TENANT)
                .when()
                .get("/api/v1/secrets/rest-test/secret")
                .then()
                .statusCode(200)
                .body("data.key", equalTo("value"));
    }

    @Test
    @Order(22)
    @DisplayName("REST API: List secrets")
    void testRestAPIListSecrets() {
        given()
                .queryParam("tenantId", TEST_TENANT)
                .when()
                .get("/api/v1/secrets")
                .then()
                .statusCode(200)
                .body("count", greaterThanOrEqualTo(0));
    }

    @Test
    @Order(23)
    @DisplayName("REST API: Delete secret")
    void testRestAPIDeleteSecret() {
        testRestAPIStoreSecret();

        given()
                .queryParam("tenantId", TEST_TENANT)
                .queryParam("hard", "true")
                .when()
                .delete("/api/v1/secrets/rest-test/secret")
                .then()
                .statusCode(204);
    }

    @Test
    @Order(24)
    @DisplayName("REST API: Secret metadata")
    void testRestAPISecretMetadata() {
        testRestAPIStoreSecret();

        given()
                .queryParam("tenantId", TEST_TENANT)
                .when()
                .get("/api/v1/secrets/rest-test/secret/metadata")
                .then()
                .statusCode(200)
                .body("version", equalTo(1));
    }

    @Test
    @Order(25)
    @DisplayName("REST API: Health check")
    void testRestAPIHealthCheck() {
        given()
                .when()
                .get("/api/v1/secrets/health")
                .then()
                .statusCode(anyOf(is(200), is(503)));
    }

    // =========================================================================
    // Performance Tests
    // =========================================================================

    @Test
    @Order(60)
    @DisplayName("Performance: Bulk operations")
    void testBulkOperations() {
        int secretCount = 100;

        long startStore = System.currentTimeMillis();
        for (int i = 0; i < secretCount; i++) {
            StoreSecretRequest request = StoreSecretRequest.builder()
                    .tenantId(TEST_TENANT)
                    .path("perf-test/secret-" + i)
                    .data(Map.of("value", "test"))
                    .type(SecretType.GENERIC)
                    .build();

            secretManager.store(request).await().indefinitely();
        }
        long storeTime = System.currentTimeMillis() - startStore;

        long startRetrieve = System.currentTimeMillis();
        for (int i = 0; i < secretCount; i++) {
            secretManager.retrieve(
                    RetrieveSecretRequest.latest(TEST_TENANT, "perf-test/secret-" + i)).await().indefinitely();
        }
        long retrieveTime = System.currentTimeMillis() - startRetrieve;

        System.out.printf("Performance: Store=%dms, Retrieve=%dms for %d secrets%n",
                storeTime, retrieveTime, secretCount);

        assertTrue(storeTime < 30000, "Store should complete in < 30s");
        assertTrue(retrieveTime < 20000, "Retrieve should complete in < 20s");
    }

    @Test
    @Order(61)
    @DisplayName("Performance: Concurrent operations")
    void testConcurrentOperations() throws InterruptedException {
        int threadCount = 10;
        int operationsPerThread = 50;

        Thread[] threads = new Thread[threadCount];
        long startTime = System.currentTimeMillis();

        for (int t = 0; t < threadCount; t++) {
            final int threadId = t;
            threads[t] = new Thread(() -> {
                for (int i = 0; i < operationsPerThread; i++) {
                    StoreSecretRequest request = StoreSecretRequest.builder()
                            .tenantId(TEST_TENANT)
                            .path("concurrent-test/secret-" + threadId + "-" + i)
                            .data(Map.of("value", "test"))
                            .type(SecretType.GENERIC)
                            .build();

                    try {
                        secretManager.store(request).await().indefinitely();
                    } catch (Exception e) {
                        Thread.currentThread().interrupt();
                    }
                }
            });
            threads[t].start();
        }

        for (Thread thread : threads) {
            thread.join();
        }

        long totalTime = System.currentTimeMillis() - startTime;
        int totalOperations = threadCount * operationsPerThread;

        System.out.printf("Concurrent Performance: %d ops in %dms (%.2f ops/sec)%n",
                totalOperations, totalTime, (totalOperations * 1000.0) / totalTime);

        assertTrue(totalTime < 60000, "Concurrent operations should complete in < 60s");
    }
}

/**
 * Test utilities and fixtures
 */
@ApplicationScoped
class TestFixtures {

    public static Map<String, String> createTestSecret(String name) {
        return Map.of(
                "name", name,
                "value", "test-value-" + System.currentTimeMillis(),
                "timestamp", String.valueOf(System.currentTimeMillis()));
    }

    public static StoreSecretRequest createStoreRequest(String tenantId, String path) {
        return StoreSecretRequest.builder()
                .tenantId(tenantId)
                .path(path)
                .data(createTestSecret(path))
                .type(SecretType.GENERIC)
                .build();
    }

    public static void storeTestSecrets(SecretManager manager, String tenantId, int count) {
        for (int i = 0; i < count; i++) {
            StoreSecretRequest request = createStoreRequest(
                    tenantId,
                    "test-fixture/secret-" + i);
            manager.store(request).await().indefinitely();
        }
    }
}
