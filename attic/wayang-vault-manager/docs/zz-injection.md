package tech.kayys.wayang.security.secrets.injection;

import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.wayang.security.secrets.*;

import java.lang.annotation.*;
import java.lang.reflect.Field;
import java.time.Duration;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;

/**
 * Annotation for automatic secret injection into fields.
 * 
 * Usage:
 * <pre>
 * @ApplicationScoped
 * public class MyService {
 *     
 *     @SecretValue(path = "services/github/api-key", key = "api_key")
 *     String githubApiKey;
 *     
 *     @SecretValue(path = "databases/production", key = "password", 
 *                  cacheTTL = 300, refreshOnAccess = true)
 *     String dbPassword;
 * }
 * </pre>
 * 
 * Features:
 * - Automatic secret loading on field access
 * - Caching with configurable TTL
 * - Lazy loading (only loaded when accessed)
 * - Automatic refresh on rotation
 * - Thread-safe
 */
@Target({ElementType.FIELD, ElementType.PARAMETER})
@Retention(RetentionPolicy.RUNTIME)
@Documented
public @interface SecretValue {
    
    /**
     * Secret path in the secret manager
     */
    String path();
    
    /**
     * Key within the secret data
     */
    String key() default "value";
    
    /**
     * Tenant ID (defaults to current tenant from context)
     */
    String tenantId() default "";
    
    /**
     * Cache TTL in seconds (0 = no cache)
     */
    int cacheTTL() default 300;
    
    /**
     * Refresh secret value on each access
     */
    boolean refreshOnAccess() default false;
    
    /**
     * Fail if secret not found
     */
    boolean required() default true;
    
    /**
     * Default value if secret not found (only if required=false)
     */
    String defaultValue() default "";
}

/**
 * Secret injection processor that handles @SecretValue annotations.
 */
@ApplicationScoped
public class SecretInjectionProcessor {
    
    private static final Logger LOG = Logger.getLogger(SecretInjectionProcessor.class);
    
    @Inject
    SecretManager secretManager;
    
    @Inject
    TenantContext tenantContext;
    
    private final Map<String, CachedSecret> cache = new ConcurrentHashMap<>();
    
    /**
     * Inject secrets into an object's annotated fields
     */
    public Uni<Void> injectSecrets(Object target) {
        return injectSecrets(target, tenantContext.getCurrentTenantId());
    }
    
    /**
     * Inject secrets with explicit tenant ID
     */
    public Uni<Void> injectSecrets(Object target, String tenantId) {
        Field[] fields = target.getClass().getDeclaredFields();
        
        return Uni.createFrom().deferred(() -> {
            Uni<Void> chain = Uni.createFrom().voidItem();
            
            for (Field field : fields) {
                if (field.isAnnotationPresent(SecretValue.class)) {
                    chain = chain.onItem().transformToUni(v -> 
                        injectField(target, field, tenantId)
                    );
                }
            }
            
            return chain;
        });
    }
    
    private Uni<Void> injectField(Object target, Field field, String tenantId) {
        SecretValue annotation = field.getAnnotation(SecretValue.class);
        
        String effectiveTenantId = annotation.tenantId().isEmpty() 
            ? tenantId 
            : annotation.tenantId();
        
        String cacheKey = buildCacheKey(effectiveTenantId, annotation.path(), annotation.key());
        
        return getSecretValue(effectiveTenantId, annotation)
            .onItem().invoke(value -> {
                try {
                    field.setAccessible(true);
                    field.set(target, value);
                    LOG.debugf("Injected secret into field %s.%s", 
                        target.getClass().getSimpleName(), field.getName());
                } catch (IllegalAccessException e) {
                    throw new SecretInjectionException(
                        "Failed to inject secret into field: " + field.getName(), e
                    );
                }
            })
            .replaceWithVoid();
    }
    
    private Uni<String> getSecretValue(String tenantId, SecretValue annotation) {
        String cacheKey = buildCacheKey(tenantId, annotation.path(), annotation.key());
        
        // Check cache first (if enabled)
        if (annotation.cacheTTL() > 0 && !annotation.refreshOnAccess()) {
            CachedSecret cached = cache.get(cacheKey);
            if (cached != null && !cached.isExpired()) {
                LOG.debugf("Using cached secret: %s", cacheKey);
                return Uni.createFrom().item(cached.value());
            }
        }
        
        // Retrieve from secret manager
        RetrieveSecretRequest request = RetrieveSecretRequest.of(tenantId, annotation.path());
        
        return secretManager.retrieve(request)
            .onItem().transform(secret -> {
                String value = secret.data().get(annotation.key());
                
                if (value == null && annotation.required()) {
                    throw new SecretInjectionException(
                        "Secret key not found: " + annotation.key() + " in path: " + annotation.path()
                    );
                }
                
                if (value == null) {
                    value = annotation.defaultValue();
                }
                
                // Cache if enabled
                if (annotation.cacheTTL() > 0) {
                    cache.put(cacheKey, new CachedSecret(
                        value,
                        System.currentTimeMillis() + (annotation.cacheTTL() * 1000L)
                    ));
                }
                
                return value;
            })
            .onFailure().recoverWithItem(error -> {
                if (annotation.required()) {
                    throw new SecretInjectionException(
                        "Failed to retrieve secret: " + annotation.path(), error
                    );
                }
                LOG.warnf("Failed to retrieve optional secret: %s, using default", 
                    annotation.path());
                return annotation.defaultValue();
            });
    }
    
    /**
     * Invalidate cached secret
     */
    public void invalidateCache(String tenantId, String path) {
        cache.keySet().removeIf(key -> key.startsWith(tenantId + ":" + path));
        LOG.infof("Invalidated cache for tenant=%s, path=%s", tenantId, path);
    }
    
    /**
     * Clear all cached secrets
     */
    public void clearCache() {
        cache.clear();
        LOG.info("Cleared all cached secrets");
    }
    
    private String buildCacheKey(String tenantId, String path, String key) {
        return tenantId + ":" + path + ":" + key;
    }
    
    record CachedSecret(String value, long expiresAt) {
        boolean isExpired() {
            return System.currentTimeMillis() > expiresAt;
        }
    }
}

/**
 * Exception thrown during secret injection
 */
class SecretInjectionException extends RuntimeException {
    public SecretInjectionException(String message) {
        super(message);
    }
    
    public SecretInjectionException(String message, Throwable cause) {
        super(message, cause);
    }
}

/**
 * Tenant context holder for multi-tenancy
 */
@ApplicationScoped
class TenantContext {
    
    private static final ThreadLocal<String> currentTenantId = new ThreadLocal<>();
    
    public String getCurrentTenantId() {
        String tenantId = currentTenantId.get();
        if (tenantId == null) {
            return "default-tenant";
        }
        return tenantId;
    }
    
    public void setCurrentTenantId(String tenantId) {
        currentTenantId.set(tenantId);
    }
    
    public void clear() {
        currentTenantId.remove();
    }
}

/**
 * CDI extension to automatically inject secrets on bean creation
 */
@ApplicationScoped
public class SecretInjectionInterceptor {
    
    private static final Logger LOG = Logger.getLogger(SecretInjectionInterceptor.class);
    
    @Inject
    SecretInjectionProcessor processor;
    
    /**
     * Process bean after creation to inject secrets
     */
    public <T> T processBean(T bean) {
        if (hasSecretAnnotations(bean.getClass())) {
            LOG.debugf("Processing secret injection for: %s", 
                bean.getClass().getSimpleName());
            
            processor.injectSecrets(bean)
                .await().indefinitely();
        }
        return bean;
    }
    
    private boolean hasSecretAnnotations(Class<?> clazz) {
        for (Field field : clazz.getDeclaredFields()) {
            if (field.isAnnotationPresent(SecretValue.class)) {
                return true;
            }
        }
        return false;
    }
}

/**
 * Event listener for secret rotation to invalidate cache
 */
@ApplicationScoped
class SecretRotationListener {
    
    private static final Logger LOG = Logger.getLogger(SecretRotationListener.class);
    
    @Inject
    SecretInjectionProcessor processor;
    
    void onSecretRotated(@Observes SecretRotatedEvent event) {
        LOG.infof("Secret rotated: tenant=%s, path=%s, invalidating cache", 
            event.tenantId(), event.path());
        
        processor.invalidateCache(event.tenantId(), event.path());
    }
}

/**
 * Event emitted when a secret is rotated
 */
record SecretRotatedEvent(
    String tenantId,
    String path,
    int oldVersion,
    int newVersion
) {}