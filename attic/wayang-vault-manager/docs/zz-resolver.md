package tech.kayys.wayang.workflow.execution.secrets;

import io.smallrye.mutiny.Multi;
import io.smallrye.mutiny.Uni;
import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import org.jboss.logging.Logger;
import tech.kayys.wayang.schema.node.NodeDescriptor;
import tech.kayys.wayang.schema.security.SecretRef;
import tech.kayys.wayang.schema.security.SecretAwarePortDescriptor;
import tech.kayys.wayang.security.secrets.*;
import tech.kayys.wayang.workflow.execution.NodeContext;

import java.util.*;
import java.util.stream.Collectors;

/**
 * Resolves secret references in node execution context.
 * 
 * Integrated into AbstractNode execution flow to automatically
 * load secrets before node execution.
 * 
 * Features:
 * - Automatic secret loading based on port descriptors
 * - Batch secret retrieval for performance
 * - Validation of secret values
 * - Error handling with fallbacks
 * - Audit logging
 */
@ApplicationScoped
public class SecretResolver {
    
    private static final Logger LOG = Logger.getLogger(SecretResolver.class);
    
    @Inject
    SecretManager secretManager;
    
    @Inject
    SecretCache secretCache;
    
    @Inject
    SecretValidator secretValidator;
    
    @Inject
    ProvenanceService provenanceService;
    
    /**
     * Resolve all secret references in node context
     */
    public Uni<NodeContext> resolveSecrets(NodeContext context, NodeDescriptor descriptor) {
        List<SecretResolutionTask> tasks = identifySecretTasks(descriptor);
        
        if (tasks.isEmpty()) {
            LOG.debugf("No secrets to resolve for node: %s", descriptor.getId());
            return Uni.createFrom().item(context);
        }
        
        LOG.infof("Resolving %d secrets for node: %s", tasks.size(), descriptor.getId());
        
        return Multi.createFrom().iterable(tasks)
            .onItem().transformToUniAndMerge(task -> 
                resolveSecret(context.getTenantId(), task)
            )
            .collect().asList()
            .onItem().transform(resolvedSecrets -> {
                NodeContext enrichedContext = context.copy();
                
                // Inject resolved secrets into context
                for (ResolvedSecret resolved : resolvedSecrets) {
                    enrichedContext.setInput(resolved.inputName(), resolved.value());
                    
                    // Mark as sensitive for redaction in logs
                    enrichedContext.markSensitive(resolved.inputName());
                }
                
                // Log provenance
                logSecretResolution(context, resolvedSecrets);
                
                return enrichedContext;
            })
            .onFailure().transform(error -> 
                new SecretResolutionException(
                    "Failed to resolve secrets for node: " + descriptor.getId(),
                    error
                )
            );
    }
    
    /**
     * Identify all secret resolution tasks from node descriptor
     */
    private List<SecretResolutionTask> identifySecretTasks(NodeDescriptor descriptor) {
        List<SecretResolutionTask> tasks = new ArrayList<>();
        
        if (descriptor.getInputs() != null) {
            for (var input : descriptor.getInputs()) {
                if (input instanceof SecretAwarePortDescriptor secretPort) {
                    if (secretPort.requiresSecretResolution()) {
                        SecretRef ref = secretPort.getSecretRef().orElseThrow();
                        tasks.add(new SecretResolutionTask(
                            input.getName(),
                            ref,
                            secretPort.getData().getRequired()
                        ));
                    }
                }
            }
        }
        
        return tasks;
    }
    
    /**
     * Resolve a single secret
     */
    private Uni<ResolvedSecret> resolveSecret(String tenantId, SecretResolutionTask task) {
        SecretRef ref = task.secretRef();
        
        // Check cache first
        if (ref.getCacheTTL() != null && ref.getCacheTTL() > 0 && 
            !Boolean.TRUE.equals(ref.getRefreshOnAccess())) {
            
            Optional<String> cached = secretCache.get(tenantId, ref.getPath(), ref.getKey());
            if (cached.isPresent()) {
                LOG.debugf("Using cached secret: %s/%s", ref.getPath(), ref.getKey());
                return Uni.createFrom().item(
                    new ResolvedSecret(task.inputName(), cached.get(), true)
                );
            }
        }
        
        // Retrieve from secret manager
        RetrieveSecretRequest request = RetrieveSecretRequest.of(tenantId, ref.getPath());
        
        return secretManager.retrieve(request)
            .onItem().transform(secret -> {
                String value = secret.data().get(ref.getKey());
                
                if (value == null) {
                    if (Boolean.TRUE.equals(ref.getRequired())) {
                        throw new SecretResolutionException(
                            "Secret key not found: " + ref.getKey() + 
                            " in path: " + ref.getPath()
                        );
                    }
                    value = ref.getDefaultValue();
                }
                
                // Validate secret value
                if (ref.getValidation() != null && !ref.getValidation().isBlank()) {
                    secretValidator.validate(value, ref.getValidation());
                }
                
                // Cache if enabled
                if (ref.getCacheTTL() != null && ref.getCacheTTL() > 0) {
                    secretCache.put(
                        tenantId, 
                        ref.getPath(), 
                        ref.getKey(), 
                        value, 
                        ref.getCacheTTL()
                    );
                }
                
                return new ResolvedSecret(task.inputName(), value, false);
            })
            .onFailure().recoverWithItem(error -> {
                if (Boolean.TRUE.equals(ref.getRequired())) {
                    throw new SecretResolutionException(
                        "Failed to resolve required secret: " + ref.getPath(),
                        error
                    );
                }
                
                LOG.warnf("Failed to resolve optional secret %s, using default: %s", 
                    ref.getPath(), error.getMessage());
                
                return new ResolvedSecret(
                    task.inputName(), 
                    ref.getDefaultValue() != null ? ref.getDefaultValue() : "",
                    false
                );
            });
    }
    
    private void logSecretResolution(NodeContext context, List<ResolvedSecret> secrets) {
        Map<String, Object> metadata = Map.of(
            "nodeId", context.getNodeId(),
            "secretCount", secrets.size(),
            "cachedCount", secrets.stream().filter(ResolvedSecret::fromCache).count(),
            "secretPaths", secrets.stream()
                .map(s -> s.inputName())
                .collect(Collectors.toList())
        );
        
        provenanceService.log(AuditPayload.builder()
            .runId(context.getRunId())
            .nodeId(context.getNodeId())
            .systemActor()
            .event("SECRETS_RESOLVED")
            .level(AuditLevel.INFO)
            .metadata(metadata)
            .build());
    }
    
    record SecretResolutionTask(
        String inputName,
        SecretRef secretRef,
        Boolean required
    ) {}
    
    record ResolvedSecret(
        String inputName,
        String value,
        boolean fromCache
    ) {}
}

/**
 * Secret cache for performance optimization
 */
@ApplicationScoped
public class SecretCache {
    
    private static final Logger LOG = Logger.getLogger(SecretCache.class);
    
    private final Map<String, CachedSecretEntry> cache = new ConcurrentHashMap<>();
    
    public Optional<String> get(String tenantId, String path, String key) {
        String cacheKey = buildKey(tenantId, path, key);
        CachedSecretEntry entry = cache.get(cacheKey);
        
        if (entry != null && !entry.isExpired()) {
            return Optional.of(entry.value());
        }
        
        // Remove expired entry
        if (entry != null) {
            cache.remove(cacheKey);
        }
        
        return Optional.empty();
    }
    
    public void put(String tenantId, String path, String key, String value, int ttlSeconds) {
        String cacheKey = buildKey(tenantId, path, key);
        long expiresAt = System.currentTimeMillis() + (ttlSeconds * 1000L);
        
        cache.put(cacheKey, new CachedSecretEntry(value, expiresAt));
        
        LOG.debugf("Cached secret: %s (TTL: %ds)", cacheKey, ttlSeconds);
    }
    
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
 * Secret validator for CEL expressions
 */
@ApplicationScoped
public class SecretValidator {
    
    private static final Logger LOG = Logger.getLogger(SecretValidator.class);
    
    public void validate(String value, String celExpression) {
        // Placeholder for CEL validation
        // In production, integrate with dev.cel:cel library
        
        try {
            // Example validations
            if (celExpression.contains("size(value)")) {
                int minSize = extractMinSize(celExpression);
                if (value.length() < minSize) {
                    throw new SecretValidationException(
                        "Secret value too short: expected at least " + minSize + " characters"
                    );
                }
            }
            
            if (celExpression.contains("matches(value")) {
                String pattern = extractPattern(celExpression);
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
    
    private int extractMinSize(String expression) {
        // Parse "size(value) >= 32" -> 32
        // Simplified extraction
        return 32; // Default
    }
    
    private String extractPattern(String expression) {
        // Parse "matches(value, '[a-zA-Z0-9]+')" -> "[a-zA-Z0-9]+"
        return ".*"; // Default
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

/**
 * Extension to NodeContext for secret handling
 */
public class SecretAwareNodeContext extends NodeContext {
    
    private final Set<String> sensitiveInputs = new HashSet<>();
    
    public void markSensitive(String inputName) {
        sensitiveInputs.add(inputName);
    }
    
    public boolean isSensitive(String inputName) {
        return sensitiveInputs.contains(inputName);
    }
    
    @Override
    public String toString() {
        // Redact sensitive values in toString for logging
        Map<String, Object> redacted = new HashMap<>();
        
        for (Map.Entry<String, Object> entry : getInputs().entrySet()) {
            if (isSensitive(entry.getKey())) {
                redacted.put(entry.getKey(), "***REDACTED***");
            } else {
                redacted.put(entry.getKey(), entry.getValue());
            }
        }
        
        return "NodeContext{" +
            "nodeId='" + getNodeId() + '\'' +
            ", inputs=" + redacted +
            '}';
    }
}