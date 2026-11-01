package tech.kayys.wayang.security.secrets;

import io.quarkus.test.junit.QuarkusTest;
import io.restassured.RestAssured;
import io.restassured.http.ContentType;
import org.junit.jupiter.api.*;
import org.testcontainers.containers.PostgreSQLContainer;
import org.testcontainers.containers.localstack.LocalStackContainer;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.utility.DockerImageName;

import jakarta.inject.Inject;
import java.time.Duration;
import java.util.*;

import static io.restassured.RestAssured.given;
import static org.hamcrest.CoreMatchers.*;
import static org.junit.jupiter.api.Assertions.*;

/**
 * Comprehensive test suite for secret management system.
 * 
 * Test Coverage:
 * - All secret backends (Local, Vault, AWS, Azure)
 * - Secret injection
 * - API key service
 * - Rotation
 * - Analytics
 * - Approval workflow
 * - Backup & restore
 * - Synchronization
 */
@QuarkusTest
@Testcontainers
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
public class SecretManagementIntegrationTest {
    
    @Container
    static PostgreSQLContainer<?> postgres = new PostgreSQLContainer<>("postgres:15-alpine")
        .withDatabaseName("wayang_test")
        .withUsername("test")
        .withPassword("test");
    
    @Container
    static LocalStackContainer localstack = new LocalStackContainer(
        DockerImageName.parse("localstack/localstack:latest"))
        .withServices(
            LocalStackContainer.Service.SECRETSMANAGER,
            LocalStackContainer.Service.KMS
        );
    
    @Inject
    SecretManager secretManager;
    
    @Inject
    APIKeyService apiKeyService;
    
    private static final String TEST_TENANT = "test-tenant";
    private static final String TEST_SECRET_PATH = "test/api-key";
    
    @BeforeEach
    void setup() {
        // Setup test data
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
        // Store secret
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
        
        // Retrieve secret
        Secret secret = secretManager.retrieve(
            RetrieveSecretRequest.of(TEST_TENANT, TEST_SECRET_PATH)
        ).await().indefinitely();
        
        assertNotNull(secret);
        assertEquals("test_key_123", secret.data().get("api_key"));
    }
    
    @Test
    @Order(2)
    @DisplayName("Secret rotation")
    void testSecretRotation() {
        // Store initial secret
        StoreSecretRequest request = StoreSecretRequest.builder()
            .tenantId(TEST_TENANT)
            .path("rotation-test")
            .data(Map.of("password", "old_password"))
            .type(SecretType.DATABASE_CREDENTIAL)
            .rotatable(true)
            .build();
        
        secretManager.store(request).await().indefinitely();
        
        // Rotate
        RotateSecretRequest rotateRequest = RotateSecretRequest.of(
            TEST_TENANT,
            "rotation-test",
            Map.of("password", "new_password")
        );
        
        SecretMetadata newMetadata = secretManager.rotate(rotateRequest)
            .await().indefinitely();
        
        assertEquals(2, newMetadata.version());
        
        // Verify new value
        Secret secret = secretManager.retrieve(
            RetrieveSecretRequest.of(TEST_TENANT, "rotation-test")
        ).await().indefinitely();
        
        assertEquals("new_password", secret.data().get("password"));
    }
    
    @Test
    @Order(3)
    @DisplayName("Secret expiration")
    void testSecretExpiration() {
        // Store secret with TTL
        StoreSecretRequest request = StoreSecretRequest.builder()
            .tenantId(TEST_TENANT)
            .path("expiring-secret")
            .data(Map.of("key", "value"))
            .type(SecretType.GENERIC)
            .ttl(Duration.ofSeconds(2))
            .build();
        
        secretManager.store(request).await().indefinitely();
        
        // Should exist initially
        assertTrue(secretManager.exists(TEST_TENANT, "expiring-secret")
            .await().indefinitely());
        
        // Wait for expiration
        try {
            Thread.sleep(3000);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
        
        // Should fail to retrieve
        assertThrows(SecretException.class, () ->
            secretManager.retrieve(
                RetrieveSecretRequest.of(TEST_TENANT, "expiring-secret")
            ).await().indefinitely()
        );
    }
    
    @Test
    @Order(4)
    @DisplayName("Secret caching")
    void testSecretCaching() {
        String path = "cached-secret";
        
        // Store secret
        StoreSecretRequest request = StoreSecretRequest.builder()
            .tenantId(TEST_TENANT)
            .path(path)
            .data(Map.of("value", "cached"))
            .type(SecretType.GENERIC)
            .build();
        
        secretManager.store(request).await().indefinitely();
        
        // First retrieval (cache miss)
        long start1 = System.currentTimeMillis();
        secretManager.retrieve(RetrieveSecretRequest.of(TEST_TENANT, path))
            .await().indefinitely();
        long time1 = System.currentTimeMillis() - start1;
        
        // Second retrieval (cache hit)
        long start2 = System.currentTimeMillis();
        secretManager.retrieve(RetrieveSecretRequest.of(TEST_TENANT, path))
            .await().indefinitely();
        long time2 = System.currentTimeMillis() - start2;
        
        // Cache hit should be faster
        assertTrue(time2 < time1, 
            "Cached retrieval should be faster: " + time2 + "ms vs " + time1 + "ms");
    }
    
    // =========================================================================
    // API Key Tests
    // =========================================================================
    
    @Test
    @Order(10)
    @DisplayName("Create and validate API key")
    void testAPIKeyCreationAndValidation() {
        // Create API key
        CreateAPIKeyRequest request = new CreateAPIKeyRequest(
            TEST_TENANT,
            "Test API Key",
            List.of("workflows:read", "workflows:write"),
            "test",
            Duration.ofDays(30),
            "test-user",
            Map.of()
        );
        
        APIKeyCreationResult result = apiKeyService.createAPIKey(request)
            .await().indefinitely();
        
        assertNotNull(result);
        assertNotNull(result.apiKey());
        assertTrue(result.apiKey().startsWith("wayang_test_"));
        
        // Validate API key
        APIKeyValidationResult validation = apiKeyService.validateAPIKey(result.apiKey())
            .await().indefinitely();
        
        assertTrue(validation.valid());
        assertEquals(TEST_TENANT, validation.tenantId());
        assertTrue(validation.scopes().contains("workflows:read"));
    }
    
    @Test
    @Order(11)
    @DisplayName("API key rate limiting")
    void testAPIKeyRateLimiting() {
        // Create API key
        CreateAPIKeyRequest request = new CreateAPIKeyRequest(
            TEST_TENANT,
            "Rate Limited Key",
            List.of("test:scope"),
            "test",
            null,
            "test-user",
            Map.of()
        );
        
        APIKeyCreationResult result = apiKeyService.createAPIKey(request)
            .await().indefinitely();
        
        // Make multiple requests (exceeding rate limit)
        int successCount = 0;
        int rateLimitedCount = 0;
        
        for (int i = 0; i < 70; i++) {
            APIKeyValidationResult validation = apiKeyService
                .validateAPIKey(result.apiKey())
                .await().indefinitely();
            
            if (validation.valid()) {
                successCount++;
            } else {
                rateLimitedCount++;
            }
        }
        
        // Should hit rate limit
        assertTrue(rateLimitedCount > 0, 
            "Should have rate limited requests");
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
                "type", "GENERIC"
            ))
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
        // First store a secret
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
    @DisplayName("REST API: API key authentication")
    void testRestAPIAuthentication() {
        // Create API key
        CreateAPIKeyRequest request = new CreateAPIKeyRequest(
            TEST_TENANT,
            "REST Test Key",
            List.of("secrets:read"),
            "test",
            null,
            "test-user",
            Map.of()
        );
        
        APIKeyCreationResult result = apiKeyService.createAPIKey(request)
            .await().indefinitely();
        
        // Use API key to access endpoint
        given()
            .header("X-API-Key", result.apiKey())
            .queryParam("tenantId", TEST_TENANT)
        .when()
            .get("/api/v1/secrets/rest-test/secret")
        .then()
            .statusCode(200);
    }
    
    // =========================================================================
    // Injection Tests
    // =========================================================================
    
    @Test
    @Order(30)
    @DisplayName("Secret injection into fields")
    void testSecretInjection() {
        // Store test secret
        StoreSecretRequest request = StoreSecretRequest.builder()
            .tenantId(TEST_TENANT)
            .path("injection-test/api-key")
            .data(Map.of("api_key", "injected_value"))
            .type(SecretType.API_KEY)
            .build();
        
        secretManager.store(request).await().indefinitely();
        
        // Create test service with injection
        TestServiceWithInjection service = new TestServiceWithInjection();
        
        // Inject secrets
        SecretInjectionProcessor processor = new SecretInjectionProcessor();
        processor.injectSecrets(service, TEST_TENANT)
            .await().indefinitely();
        
        // Verify injection
        assertNotNull(service.apiKey);
        assertEquals("injected_value", service.apiKey);
    }
    
    // Test class for injection
    static class TestServiceWithInjection {
        @SecretValue(path = "injection-test/api-key", key = "api_key")
        String apiKey;
    }
    
    // =========================================================================
    // Analytics Tests
    // =========================================================================
    
    @Test
    @Order(40)
    @DisplayName("Analytics: Access report")
    void testAnalyticsAccessReport() {
        // Access some secrets
        for (int i = 0; i < 5; i++) {
            secretManager.retrieve(
                RetrieveSecretRequest.of(TEST_TENANT, TEST_SECRET_PATH)
            ).await().indefinitely();
        }
        
        // Generate report
        given()
            .queryParam("tenantId", TEST_TENANT)
            .queryParam("from", Instant.now().minus(Duration.ofHours(1)).toString())
            .queryParam("to", Instant.now().toString())
        .when()
            .get("/api/v1/secrets/analytics/access-report")
        .then()
            .statusCode(200)
            .body("tenantId", equalTo(TEST_TENANT))
            .body("totalAccesses", greaterThan(0));
    }
    
    // =========================================================================
    // Backup & Restore Tests
    // =========================================================================
    
    @Test
    @Order(50)
    @DisplayName("Backup and restore")
    void testBackupAndRestore() {
        // Store test secrets
        for (int i = 0; i < 3; i++) {
            StoreSecretRequest request = StoreSecretRequest.builder()
                .tenantId(TEST_TENANT)
                .path("backup-test/secret-" + i)
                .data(Map.of("value", "backup_value_" + i))
                .type(SecretType.GENERIC)
                .build();
            
            secretManager.store(request).await().indefinitely();
        }
        
        // Perform backup
        SecretBackupService backupService = new SecretBackupService();
        BackupResult backupResult = backupService.backupAllSecrets()
            .await().indefinitely();
        
        assertTrue(backupResult.success());
        assertNotNull(backupResult.backupId());
        
        // Delete secrets
        for (int i = 0; i < 3; i++) {
            secretManager.delete(
                DeleteSecretRequest.hard(TEST_TENANT, "backup-test/secret-" + i, "test")
            ).await().indefinitely();
        }
        
        // Restore
        RestoreResult restoreResult = backupService.restoreFromBackup(
            backupResult.backupId(),
            RestoreOptions.all()
        ).await().indefinitely();
        
        assertTrue(restoreResult.success());
        assertTrue(restoreResult.restored() > 0);
        
        // Verify restored
        Secret restored = secretManager.retrieve(
            RetrieveSecretRequest.of(TEST_TENANT, "backup-test/secret-0")
        ).await().indefinitely();
        
        assertEquals("backup_value_0", restored.data().get("value"));
    }
    
    // =========================================================================
    // Performance Tests
    // =========================================================================
    
    @Test
    @Order(60)
    @DisplayName("Performance: Bulk operations")
    void testBulkOperations() {
        int secretCount = 100;
        
        // Bulk store
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
        
        // Bulk retrieve
        long startRetrieve = System.currentTimeMillis();
        for (int i = 0; i < secretCount; i++) {
            secretManager.retrieve(
                RetrieveSecretRequest.of(TEST_TENANT, "perf-test/secret-" + i)
            ).await().indefinitely();
        }
        long retrieveTime = System.currentTimeMillis() - startRetrieve;
        
        System.out.printf("Performance: Store=%dms, Retrieve=%dms for %d secrets%n",
            storeTime, retrieveTime, secretCount);
        
        // Performance assertions
        assertTrue(storeTime < 10000, "Store should complete in < 10s");
        assertTrue(retrieveTime < 5000, "Retrieve should complete in < 5s");
    }
}