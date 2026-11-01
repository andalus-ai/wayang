package tech.kayys.wayang.security.apikey;

import io.quarkus.hibernate.reactive.panache.Panache;
import io.quarkus.hibernate.reactive.panache.PanacheRepositoryBase;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import jakarta.persistence.*;
import org.jboss.logging.Logger;

import java.security.SecureRandom;
import java.time.Instant;
import java.time.Duration;
import java.util.*;
import java.util.stream.Collectors;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;

/**
 * API Key Service for platform authentication and authorization.
 * 
 * Features:
 * - Secure API key generation (cryptographically random)
 * - Hashed storage (never store plaintext)
 * - Scope-based permissions
 * - Rate limiting
 * - Expiration and rotation
 * - Usage tracking
 * - Multi-tenancy support
 * 
 * API Key Format: wayang_live_<random_32_chars> or wayang_test_<random_32_chars>
 */
@ApplicationScoped
public class APIKeyService {
    
    private static final Logger LOG = Logger.getLogger(APIKeyService.class);
    private static final String KEY_PREFIX_LIVE = "wayang_live_";
    private static final String KEY_PREFIX_TEST = "wayang_test_";
    private static final int KEY_LENGTH = 32;
    private static final String HASH_ALGORITHM = "HmacSHA256";
    
    @Inject
    APIKeyRepository repository;
    
    @Inject
    APIKeyHasher hasher;
    
    @Inject
    RateLimiter rateLimiter;
    
    @Inject
    ProvenanceService provenanceService;
    
    private final SecureRandom secureRandom = new SecureRandom();
    
    /**
     * Create a new API key
     */
    public Uni<APIKeyCreationResult> createAPIKey(CreateAPIKeyRequest request) {
        LOG.infof("Creating API key for tenant: %s, name: %s", 
            request.tenantId(), request.name());
        
        return Panache.withTransaction(() -> {
            // Generate random key
            String plainKey = generateKey(request.environment());
            String hashedKey = hasher.hash(plainKey);
            
            // Create entity
            APIKeyEntity entity = new APIKeyEntity();
            entity.tenantId = request.tenantId();
            entity.name = request.name();
            entity.hashedKey = hashedKey;
            entity.prefix = extractPrefix(plainKey);
            entity.environment = request.environment();
            entity.scopes = new HashSet<>(request.scopes());
            entity.createdAt = Instant.now();
            entity.createdBy = request.createdBy();
            entity.status = APIKeyStatus.ACTIVE;
            
            if (request.expiresIn() != null) {
                entity.expiresAt = Instant.now().plus(request.expiresIn());
            }
            
            entity.metadata = request.metadata() != null ? 
                new HashMap<>(request.metadata()) : new HashMap<>();
            
            return repository.persist(entity)
                .onItem().invoke(saved -> {
                    logProvenance(saved, "API_KEY_CREATED");
                })
                .onItem().transform(saved -> 
                    new APIKeyCreationResult(
                        saved.id.toString(),
                        plainKey, // Only returned once!
                        saved.prefix,
                        saved.scopes,
                        saved.expiresAt,
                        "Store this key securely - it will not be shown again"
                    )
                );
        });
    }
    
    /**
     * Validate an API key and return associated metadata
     */
    public Uni<APIKeyValidationResult> validateAPIKey(String apiKey) {
        String hashedKey = hasher.hash(apiKey);
        String prefix = extractPrefix(apiKey);
        
        return repository.findByHashedKey(hashedKey)
            .onItem().transformToUni(entityOpt -> {
                if (entityOpt.isEmpty()) {
                    LOG.warnf("API key not found: prefix=%s", prefix);
                    return Uni.createFrom().item(
                        APIKeyValidationResult.invalid("API key not found")
                    );
                }
                
                APIKeyEntity entity = entityOpt.get();
                
                // Check status
                if (entity.status != APIKeyStatus.ACTIVE) {
                    return Uni.createFrom().item(
                        APIKeyValidationResult.invalid("API key is " + entity.status)
                    );
                }
                
                // Check expiration
                if (entity.expiresAt != null && Instant.now().isAfter(entity.expiresAt)) {
                    entity.status = APIKeyStatus.EXPIRED;
                    return repository.persist(entity)
                        .onItem().transform(v -> 
                            APIKeyValidationResult.invalid("API key expired")
                        );
                }
                
                // Check rate limit
                return rateLimiter.checkLimit(entity.id.toString())
                    .onItem().transformToUni(allowed -> {
                        if (!allowed) {
                            return Uni.createFrom().item(
                                APIKeyValidationResult.invalid("Rate limit exceeded")
                            );
                        }
                        
                        // Update last used
                        entity.lastUsedAt = Instant.now();
                        entity.usageCount++;
                        
                        return repository.persist(entity)
                            .onItem().transform(updated -> 
                                APIKeyValidationResult.valid(
                                    updated.id.toString(),
                                    updated.tenantId,
                                    updated.name,
                                    updated.scopes,
                                    updated.environment,
                                    updated.metadata
                                )
                            );
                    });
            });
    }
    
    /**
     * List API keys for a tenant (never returns actual keys)
     */
    public Uni<List<APIKeyInfo>> listAPIKeys(String tenantId) {
        return repository.findByTenant(tenantId)
            .onItem().transform(entities ->
                entities.stream()
                    .map(this::toAPIKeyInfo)
                    .collect(Collectors.toList())
            );
    }
    
    /**
     * Revoke an API key
     */
    public Uni<Void> revokeAPIKey(String keyId, String reason) {
        LOG.infof("Revoking API key: %s, reason: %s", keyId, reason);
        
        return Panache.withTransaction(() ->
            repository.findById(UUID.fromString(keyId))
                .onItem().transformToUni(entityOpt -> {
                    if (entityOpt.isEmpty()) {
                        return Uni.createFrom().failure(
                            new APIKeyNotFoundException("API key not found: " + keyId)
                        );
                    }
                    
                    APIKeyEntity entity = entityOpt.get();
                    entity.status = APIKeyStatus.REVOKED;
                    entity.revokedAt = Instant.now();
                    entity.metadata.put("revocation_reason", reason);
                    
                    return repository.persist(entity)
                        .onItem().invoke(saved -> 
                            logProvenance(saved, "API_KEY_REVOKED", 
                                Map.of("reason", reason))
                        )
                        .replaceWithVoid();
                })
        );
    }
    
    /**
     * Rotate an API key (create new, revoke old)
     */
    public Uni<APIKeyCreationResult> rotateAPIKey(String keyId) {
        LOG.infof("Rotating API key: %s", keyId);
        
        return Panache.withTransaction(() ->
            repository.findById(UUID.fromString(keyId))
                .onItem().transformToUni(entityOpt -> {
                    if (entityOpt.isEmpty()) {
                        return Uni.createFrom().failure(
                            new APIKeyNotFoundException("API key not found: " + keyId)
                        );
                    }
                    
                    APIKeyEntity oldKey = entityOpt.get();
                    
                    // Create new key with same properties
                    CreateAPIKeyRequest newKeyRequest = new CreateAPIKeyRequest(
                        oldKey.tenantId,
                        oldKey.name + " (rotated)",
                        new ArrayList<>(oldKey.scopes),
                        oldKey.environment,
                        oldKey.expiresAt != null ? 
                            Duration.between(Instant.now(), oldKey.expiresAt) : null,
                        "system",
                        oldKey.metadata
                    );
                    
                    return createAPIKey(newKeyRequest)
                        .onItem().transformToUni(newKey -> {
                            // Revoke old key
                            oldKey.status = APIKeyStatus.ROTATED;
                            oldKey.revokedAt = Instant.now();
                            oldKey.metadata.put("rotated_to", newKey.id());
                            
                            return repository.persist(oldKey)
                                .onItem().invoke(saved ->
                                    logProvenance(saved, "API_KEY_ROTATED",
                                        Map.of("new_key_id", newKey.id()))
                                )
                                .onItem().transform(v -> newKey);
                        });
                })
        );
    }
    
    /**
     * Update API key scopes
     */
    public Uni<Void> updateScopes(String keyId, Set<String> scopes) {
        return Panache.withTransaction(() ->
            repository.findById(UUID.fromString(keyId))
                .onItem().transformToUni(entityOpt -> {
                    if (entityOpt.isEmpty()) {
                        return Uni.createFrom().failure(
                            new APIKeyNotFoundException("API key not found: " + keyId)
                        );
                    }
                    
                    APIKeyEntity entity = entityOpt.get();
                    Set<String> oldScopes = new HashSet<>(entity.scopes);
                    entity.scopes = new HashSet<>(scopes);
                    
                    return repository.persist(entity)
                        .onItem().invoke(saved ->
                            logProvenance(saved, "API_KEY_SCOPES_UPDATED",
                                Map.of(
                                    "old_scopes", oldScopes,
                                    "new_scopes", scopes
                                ))
                        )
                        .replaceWithVoid();
                })
        );
    }
    
    /**
     * Get API key usage statistics
     */
    public Uni<APIKeyUsageStats> getUsageStats(String keyId, Duration period) {
        return repository.findById(UUID.fromString(keyId))
            .onItem().transform(entityOpt -> {
                if (entityOpt.isEmpty()) {
                    throw new APIKeyNotFoundException("API key not found: " + keyId);
                }
                
                APIKeyEntity entity = entityOpt.get();
                
                // In production, query from usage logs/metrics
                return new APIKeyUsageStats(
                    entity.id.toString(),
                    entity.name,
                    entity.usageCount,
                    entity.lastUsedAt,
                    Map.of() // Request counts by endpoint
                );
            });
    }
    
    // Helper methods
    
    private String generateKey(String environment) {
        byte[] randomBytes = new byte[KEY_LENGTH];
        secureRandom.nextBytes(randomBytes);
        
        String randomPart = Base64.getUrlEncoder()
            .withoutPadding()
            .encodeToString(randomBytes)
            .substring(0, KEY_LENGTH);
        
        String prefix = "test".equals(environment) ? KEY_PREFIX_TEST : KEY_PREFIX_LIVE;
        return prefix + randomPart;
    }
    
    private String extractPrefix(String apiKey) {
        int underscoreIndex = apiKey.lastIndexOf('_');
        return underscoreIndex > 0 ? apiKey.substring(0, underscoreIndex + 1) : "";
    }
    
    private APIKeyInfo toAPIKeyInfo(APIKeyEntity entity) {
        return new APIKeyInfo(
            entity.id.toString(),
            entity.name,
            entity.prefix + "****",
            entity.scopes,
            entity.status,
            entity.environment,
            entity.createdAt,
            entity.lastUsedAt,
            entity.expiresAt,
            entity.usageCount
        );
    }
    
    private void logProvenance(APIKeyEntity entity, String event) {
        logProvenance(entity, event, Map.of());
    }
    
    private void logProvenance(APIKeyEntity entity, String event, 
                              Map<String, Object> extraMetadata) {
        Map<String, Object> metadata = new HashMap<>();
        metadata.put("api_key_id", entity.id.toString());
        metadata.put("tenant_id", entity.tenantId);
        metadata.put("key_name", entity.name);
        metadata.putAll(extraMetadata);
        
        provenanceService.log(AuditPayload.builder()
            .runId(UUID.randomUUID()) // No run context
            .nodeId("api-key-service")
            .systemActor()
            .event(event)
            .level(AuditLevel.INFO)
            .metadata(metadata)
            .build());
    }
}

/**
 * API Key hasher using HMAC-SHA256
 */
@ApplicationScoped
class APIKeyHasher {
    
    private static final Logger LOG = Logger.getLogger(APIKeyHasher.class);
    private static final String HASH_ALGORITHM = "HmacSHA256";
    
    @ConfigProperty(name = "apikey.hash.secret")
    Optional<String> hashSecret;
    
    private SecretKeySpec secretKey;
    
    @PostConstruct
    void init() {
        String secret = hashSecret.orElseGet(() -> {
            LOG.warn("No apikey.hash.secret configured, using ephemeral key");
            byte[] randomSecret = new byte[32];
            new SecureRandom().nextBytes(randomSecret);
            return Base64.getEncoder().encodeToString(randomSecret);
        });
        
        byte[] keyBytes = Base64.getDecoder().decode(secret);
        this.secretKey = new SecretKeySpec(keyBytes, HASH_ALGORITHM);
    }
    
    public String hash(String apiKey) {
        try {
            Mac mac = Mac.getInstance(HASH_ALGORITHM);
            mac.init(secretKey);
            byte[] hash = mac.doFinal(apiKey.getBytes());
            return Base64.getEncoder().encodeToString(hash);
        } catch (Exception e) {
            throw new RuntimeException("Failed to hash API key", e);
        }
    }
}

/**
 * Rate limiter for API keys
 */
@ApplicationScoped
class RateLimiter {
    
    private static final Logger LOG = Logger.getLogger(RateLimiter.class);
    private final Map<String, RateLimitBucket> buckets = new ConcurrentHashMap<>();
    
    @ConfigProperty(name = "apikey.rate-limit.requests-per-minute", defaultValue = "60")
    int requestsPerMinute;
    
    public Uni<Boolean> checkLimit(String keyId) {
        RateLimitBucket bucket = buckets.computeIfAbsent(
            keyId, 
            k -> new RateLimitBucket(requestsPerMinute)
        );
        
        return Uni.createFrom().item(bucket.tryConsume());
    }
    
    static class RateLimitBucket {
        private final int capacity;
        private int tokens;
        private long lastRefill;
        
        RateLimitBucket(int capacity) {
            this.capacity = capacity;
            this.tokens = capacity;
            this.lastRefill = System.currentTimeMillis();
        }
        
        synchronized boolean tryConsume() {
            refill();
            
            if (tokens > 0) {
                tokens--;
                return true;
            }
            return false;
        }
        
        private void refill() {
            long now = System.currentTimeMillis();
            long elapsed = now - lastRefill;
            
            if (elapsed >= 60000) { // 1 minute
                tokens = capacity;
                lastRefill = now;
            }
        }
    }
}

/**
 * API Key entity
 */
@Entity
@Table(name = "api_keys", indexes = {
    @Index(name = "idx_api_keys_tenant", columnList = "tenantId"),
    @Index(name = "idx_api_keys_hashed", columnList = "hashedKey"),
    @Index(name = "idx_api_keys_status", columnList = "status")
})
class APIKeyEntity {
    
    @Id
    @GeneratedValue
    UUID id;
    
    @Column(nullable = false)
    String tenantId;
    
    @Column(nullable = false)
    String name;
    
    @Column(nullable = false, unique = true)
    String hashedKey;
    
    @Column(nullable = false)
    String prefix; // For display: "wayang_live_****"
    
    @Column(nullable = false)
    String environment; // "live" or "test"
    
    @ElementCollection
    @CollectionTable(name = "api_key_scopes", joinColumns = @JoinColumn(name = "api_key_id"))
    @Column(name = "scope")
    Set<String> scopes = new HashSet<>();
    
    @Column(nullable = false)
    Instant createdAt;
    
    @Column(nullable = false)
    String createdBy;
    
    @Column
    Instant lastUsedAt;
    
    @Column
    Instant expiresAt;
    
    @Column
    Instant revokedAt;
    
    @Column(nullable = false)
    @Enumerated(EnumType.STRING)
    APIKeyStatus status;
    
    @Column(nullable = false)
    Long usageCount = 0L;
    
    @ElementCollection
    @CollectionTable(name = "api_key_metadata", joinColumns = @JoinColumn(name = "api_key_id"))
    @MapKeyColumn(name = "key")
    @Column(name = "value")
    Map<String, String> metadata = new HashMap<>();
}

/**
 * API Key repository
 */
@ApplicationScoped
class APIKeyRepository implements PanacheRepositoryBase<APIKeyEntity, UUID> {
    
    public Uni<Optional<APIKeyEntity>> findByHashedKey(String hashedKey) {
        return find("hashedKey", hashedKey)
            .firstResult()
            .onItem().transform(Optional::ofNullable);
    }
    
    public Uni<List<APIKeyEntity>> findByTenant(String tenantId) {
        return find("tenantId = ?1 order by createdAt desc", tenantId)
            .list();
    }
    
    public Uni<List<APIKeyEntity>> findExpired() {
        return find("expiresAt < ?1 and status = ?2", 
            Instant.now(), APIKeyStatus.ACTIVE)
            .list();
    }
}

// Request/Response DTOs

record CreateAPIKeyRequest(
    String tenantId,
    String name,
    List<String> scopes,
    String environment, // "live" or "test"
    Duration expiresIn,
    String createdBy,
    Map<String, String> metadata
) {
    public CreateAPIKeyRequest {
        if (tenantId == null || tenantId.isBlank()) {
            throw new IllegalArgumentException("tenantId is required");
        }
        if (name == null || name.isBlank()) {
            throw new IllegalArgumentException("name is required");
        }
        if (scopes == null || scopes.isEmpty()) {
            throw new IllegalArgumentException("scopes are required");
        }
        if (environment == null || 
            (!environment.equals("live") && !environment.equals("test"))) {
            throw new IllegalArgumentException("environment must be 'live' or 'test'");
        }
    }
}

record APIKeyCreationResult(
    String id,
    String apiKey,
    String prefix,
    Set<String> scopes,
    Instant expiresAt,
    String warning
) {}

record APIKeyValidationResult(
    boolean valid,
    String keyId,
    String tenantId,
    String name,
    Set<String> scopes,
    String environment,
    Map<String, String> metadata,
    String error
) {
    static APIKeyValidationResult valid(String keyId, String tenantId, String name,
                                       Set<String> scopes, String environment,
                                       Map<String, String> metadata) {
        return new APIKeyValidationResult(
            true, keyId, tenantId, name, scopes, environment, metadata, null
        );
    }
    
    static APIKeyValidationResult invalid(String error) {
        return new APIKeyValidationResult(
            false, null, null, null, null, null, null, error
        );
    }
}

record APIKeyInfo(
    String id,
    String name,
    String maskedKey,
    Set<String> scopes,
    APIKeyStatus status,
    String environment,
    Instant createdAt,
    Instant lastUsedAt,
    Instant expiresAt,
    long usageCount
) {}

record APIKeyUsageStats(
    String keyId,
    String name,
    long totalRequests,
    Instant lastUsed,
    Map<String, Long> requestsByEndpoint
) {}

enum APIKeyStatus {
    ACTIVE,
    REVOKED,
    EXPIRED,
    ROTATED
}

class APIKeyNotFoundException extends RuntimeException {
    public APIKeyNotFoundException(String message) {
        super(message);
    }
}

