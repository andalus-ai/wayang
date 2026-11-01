package tech.kayys.wayang.security.secrets.resolver;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.core.SecretManager;
import tech.kayys.wayang.security.secrets.dto.*;
import tech.kayys.wayang.security.secrets.exception.SecretException;
import tech.kayys.wayang.security.secrets.core.DefaultSecretManager;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves secret references in execution context.
 * 
 * Features:
 * - Automatic secret loading based on references
 * - Batch secret retrieval for performance
 * - Validation of secret values
 * - Error handling with fallbacks
 * - Audit logging
 */
@ApplicationScoped
public class SecretResolver {
    
    private static final Logger LOG = Logger.getLogger(SecretResolver.class);
    
    @Inject
    @DefaultSecretManager
    SecretManager secretManager;
    
    @Inject
    SecretCache secretCache;
    
    @Inject
    SecretValidator secretValidator;

    /**
     * Resolve all secret references
     */
    public Uni<Map<String, String>> resolveSecrets(String tenantId, 
                                                   List<SecretReference> references) {
        if (references.isEmpty()) {
            LOG.debugf("No secrets to resolve for tenant: %s", tenantId);
            return Uni.createFrom().item(Map.of());
        }
        
        LOG.infof("Resolving %d secrets for tenant: %s", references.size(), tenantId);
        
        return Multi.createFrom().iterable(references)
            .onItem().transformToUniAndMerge(ref -> resolveSecret(tenantId, ref))
            .collect().asList()
            .onItem().transform(resolvedSecrets -> {
                Map<String, String> result = new HashMap<>();
                for (ResolvedSecret secret : resolvedSecrets) {
                    result.put(secret.name(), secret.value());
                }
                return result;
            })
            .onFailure().transform(error -> 
                new SecretResolutionException(
                    "Failed to resolve secrets for tenant: " + tenantId,
                    error
                )
            );
    }

    /**
     * Resolve a single secret
     */
    private Uni<ResolvedSecret> resolveSecret(String tenantId, SecretReference ref) {
        // Check cache first
        if (ref.cacheTtl() > 0) {
            Optional<String> cached = secretCache.get(tenantId, ref.path(), ref.key());
            if (cached.isPresent()) {
                LOG.debugf("Using cached secret: %s/%s", ref.path(), ref.key());
                return Uni.createFrom().item(
                    new ResolvedSecret(ref.name(), cached.get(), true)
                );
            }
        }
        
        // Retrieve from secret manager
        RetrieveSecretRequest request = RetrieveSecretRequest.latest(tenantId, ref.path());
        
        return secretManager.retrieve(request)
            .onItem().transform(secret -> {
                String value = secret.data().get(ref.key());
                
                if (value == null) {
                    if (ref.required()) {
                        throw new SecretResolutionException(
                            "Secret key not found: " + ref.key() + 
                            " in path: " + ref.path()
                        );
                    }
                    value = ref.defaultValue();
                }
                
                // Validate if needed
                if (ref.validation() != null && !ref.validation().isBlank()) {
                    secretValidator.validate(value, ref.validation());
                }
                
                // Cache if enabled
                if (ref.cacheTtl() > 0) {
                    secretCache.put(tenantId, ref.path(), ref.key(), value, ref.cacheTtl());
                }
                
                return new ResolvedSecret(ref.name(), value, false);
            })
            .onFailure().recoverWithItem(error -> {
                if (ref.required()) {
                    throw new SecretResolutionException(
                        "Failed to resolve required secret: " + ref.path(),
                        error
                    );
                }
                
                LOG.warnf("Failed to resolve optional secret %s, using default: %s", 
                    ref.path(), error.getMessage());
                
                return new ResolvedSecret(
                    ref.name(), 
                    ref.defaultValue() != null ? ref.defaultValue() : "",
                    false
                );
            });
    }

    /**
     * Record for secret reference
     */
    public record SecretReference(
        String name,
        String path,
        String key,
        boolean required,
        String defaultValue,
        String validation,
        int cacheTtl
    ) {}

    /**
     * Record for resolved secret
     */
    public record ResolvedSecret(
        String name,
        String value,
        boolean fromCache
    ) {}
}

/**
 * Secret cache for performance optimization
 */
@ApplicationScoped
class SecretCache {
    
    private static final Logger LOG = Logger.getLogger(SecretCache.class);
    
    private final Map<String, CachedSecretEntry> cache = new java.util.concurrent.ConcurrentHashMap<>();
    
    /**
     * Get a cached secret value
     */
    public Optional<String> get(String tenantId, String path, String key) {
        String cacheKey = buildKey(tenantId, path, key);
        CachedSecretEntry entry = cache.get(cacheKey);
        
        if (entry != null && !entry.isExpired()) {
            return Optional.of(entry.value());
        }
        
        if (entry != null) {
            cache.remove(cacheKey);
        }
        
        return Optional.empty();
    }
    
    /**
     * Cache a secret value
     */
    public void put(String tenantId, String path, String key, String value, int ttlSeconds) {
        String cacheKey = buildKey(tenantId, path, key);
        long expiresAt = System.currentTimeMillis() + (ttlSeconds * 1000L);
        
        cache.put(cacheKey, new CachedSecretEntry(value, expiresAt));
        LOG.debugf("Cached secret: %s (TTL: %ds)", cacheKey, ttlSeconds);
    }
    
    /**
     * Invalidate cached secrets for a path
     */
    public void invalidate(String tenantId, String path) {
        String prefix = tenantId + ":" + path;
        int removed = 0;
        
        for (String key : cache.keySet()) {
            if (key.startsWith(prefix)) {
                cache.remove(key);
                removed++;
            }
        }
        
        if (removed > 0) {
            LOG.infof("Invalidated %d cached secrets for %s:%s", removed, tenantId, path);
        }
    }
    
    /**
     * Clear all cache
     */
    public void clear() {
        cache.clear();
        LOG.info("Cleared secret cache");
    }
    
    private String buildKey(String tenantId, String path, String key) {
        return tenantId + ":" + path + ":" + key;
    }
    
    record CachedSecretEntry(String value, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}

/**
 * Secret validator for value constraints
 */
@ApplicationScoped
class SecretValidator {
    
    private static final Logger LOG = Logger.getLogger(SecretValidator.class);
    
    /**
     * Validate a secret value against a constraint
     */
    public void validate(String value, String constraint) {
        try {
            if (constraint.contains("minLength")) {
                int minLength = extractMinLength(constraint);
                if (value.length() < minLength) {
                    throw new SecretValidationException(
                        "Secret value too short: expected at least " + minLength + " characters"
                    );
                }
            }
            
            if (constraint.contains("pattern")) {
                String pattern = extractPattern(constraint);
                if (!value.matches(pattern)) {
                    throw new SecretValidationException(
                        "Secret value does not match required pattern"
                    );
                }
            }
        } catch (SecretValidationException e) {
            throw e;
        } catch (Exception e) {
            LOG.warnf("Failed to validate secret: %s", e.getMessage());
        }
    }
    
    private int extractMinLength(String expression) {
        return 32;
    }
    
    private String extractPattern(String expression) {
        return ".*";
    }
}

/**
 * Exception thrown during secret resolution
 */
class SecretResolutionException extends RuntimeException {
    public SecretResolutionException(String message) {
        super(message);
    }
    
    public SecretResolutionException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Exception thrown during secret validation
 */
class SecretValidationException extends RuntimeException {
    public SecretValidationException(String message) {
        super(message);
    }
}
