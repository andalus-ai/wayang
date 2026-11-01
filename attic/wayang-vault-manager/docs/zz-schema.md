package tech.kayys.wayang.schema.security;

import com.fasterxml.jackson.annotation.JsonInclude;
import lombok.Data;
import tech.kayys.wayang.schema.node.PortDescriptor;

import java.util.Optional;

/**
 * Extended PortDescriptor with secret reference support.
 * 
 * This extends the existing PortDescriptorV2 to support secret references.
 * 
 * JSON Schema:
 * {
 *   "name": "apiKey",
 *   "data": {
 *     "type": "string",
 *     "source": "secret",
 *     "secretRef": {
 *       "path": "services/github/api-key",
 *       "key": "api_key",
 *       "required": true,
 *       "cacheTTL": 300
 *     }
 *   }
 * }
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class SecretRef {
    
    /**
     * Path to the secret in secret manager
     */
    private String path;
    
    /**
     * Key within the secret data map
     */
    private String key = "value";
    
    /**
     * Whether this secret is required for node execution
     */
    private Boolean required = true;
    
    /**
     * Default value if secret not found (only used if required=false)
     */
    private String defaultValue;
    
    /**
     * Cache TTL in seconds (0 = no cache)
     */
    private Integer cacheTTL = 300;
    
    /**
     * Refresh on every access (bypass cache)
     */
    private Boolean refreshOnAccess = false;
    
    /**
     * Validation expression (CEL) to validate secret value
     * Example: "size(value) >= 32" for API keys
     */
    private String validation;
    
    /**
     * Secret type hint for validation and UI
     */
    private String secretType;
    
    public SecretRef() {}
    
    public SecretRef(String path, String key) {
        this.path = path;
        this.key = key;
    }
    
    public static Builder builder() {
        return new Builder();
    }
    
    public static class Builder {
        private final SecretRef ref = new SecretRef();
        
        public Builder path(String path) {
            ref.path = path;
            return this;
        }
        
        public Builder key(String key) {
            ref.key = key;
            return this;
        }
        
        public Builder required(boolean required) {
            ref.required = required;
            return this;
        }
        
        public Builder defaultValue(String defaultValue) {
            ref.defaultValue = defaultValue;
            return this;
        }
        
        public Builder cacheTTL(int ttl) {
            ref.cacheTTL = ttl;
            return this;
        }
        
        public Builder refreshOnAccess(boolean refresh) {
            ref.refreshOnAccess = refresh;
            return this;
        }
        
        public Builder validation(String validation) {
            ref.validation = validation;
            return this;
        }
        
        public Builder secretType(String type) {
            ref.secretType = type;
            return this;
        }
        
        public SecretRef build() {
            if (ref.path == null || ref.path.isBlank()) {
                throw new IllegalArgumentException("Secret path is required");
            }
            return ref;
        }
    }
}

/**
 * Enhanced PortDataDescriptor with secret support
 */
@Data
@JsonInclude(JsonInclude.Include.NON_NULL)
public class PortDataDescriptor {
    
    private String type;
    private String format;
    private Object schema;
    private String multiplicity = "single";
    private String source = "input";
    private Boolean required = true;
    private Object defaultValue;
    private Boolean sensitive = false;
    private Object example;
    
    /**
     * Secret reference for automatic secret loading
     */
    private SecretRef secretRef;
    
    public boolean isSecret() {
        return "secret".equals(source) && secretRef != null;
    }
}

/**
 * Secret-aware port descriptor
 */
@Data
public class SecretAwarePortDescriptor extends PortDescriptor {
    
    private PortDataDescriptor data;
    
    public boolean requiresSecretResolution() {
        return data != null && data.isSecret();
    }
    
    public Optional<SecretRef> getSecretRef() {
        if (data != null && data.getSecretRef() != null) {
            return Optional.of(data.getSecretRef());
        }
        return Optional.empty();
    }
}

/**
 * Schema validator for secret references
 */
@ApplicationScoped
public class SecretRefValidator {
    
    private static final Logger LOG = Logger.getLogger(SecretRefValidator.class);
    
    /**
     * Validate secret reference configuration
     */
    public ValidationResult validate(SecretRef secretRef) {
        List<String> errors = new ArrayList<>();
        
        // Path validation
        if (secretRef.getPath() == null || secretRef.getPath().isBlank()) {
            errors.add("Secret path cannot be empty");
        }
        
        // Key validation
        if (secretRef.getKey() == null || secretRef.getKey().isBlank()) {
            errors.add("Secret key cannot be empty");
        }
        
        // TTL validation
        if (secretRef.getCacheTTL() != null && secretRef.getCacheTTL() < 0) {
            errors.add("Cache TTL must be non-negative");
        }
        
        // Validation expression (if CEL is available)
        if (secretRef.getValidation() != null && !secretRef.getValidation().isBlank()) {
            try {
                validateCELExpression(secretRef.getValidation());
            } catch (Exception e) {
                errors.add("Invalid validation expression: " + e.getMessage());
            }
        }
        
        return new ValidationResult(errors.isEmpty(), errors);
    }
    
    private void validateCELExpression(String expression) {
        // Placeholder - integrate with CEL validator
    }
}

/**
 * JSON Schema extension for secret references
 */
public class SecretRefSchemaExtension {
    
    /**
     * Generate JSON Schema definition for secret reference
     */
    public static Map<String, Object> generateSchema() {
        return Map.of(
            "$schema", "http://json-schema.org/draft-07/schema#",
            "title", "SecretRef",
            "type", "object",
            "required", List.of("path", "key"),
            "properties", Map.of(
                "path", Map.of(
                    "type", "string",
                    "description", "Path to secret in secret manager",
                    "minLength", 1
                ),
                "key", Map.of(
                    "type", "string",
                    "description", "Key within secret data",
                    "default", "value"
                ),
                "required", Map.of(
                    "type", "boolean",
                    "default", true
                ),
                "defaultValue", Map.of(
                    "type", "string",
                    "description", "Default value if secret not found"
                ),
                "cacheTTL", Map.of(
                    "type", "integer",
                    "minimum", 0,
                    "default", 300
                ),
                "refreshOnAccess", Map.of(
                    "type", "boolean",
                    "default", false
                ),
                "validation", Map.of(
                    "type", "string",
                    "description", "CEL expression for validation"
                ),
                "secretType", Map.of(
                    "type", "string",
                    "enum", List.of(
                        "api_key", "password", "token", 
                        "certificate", "encryption_key"
                    )
                )
            )
        );
    }
}

/**
 * Example workflow definition with secret references
 */
public class SecretRefExamples {
    
    public static String apiKeyExample() {
        return """
        {
          "id": "github-integration",
          "name": "GitHub API Integration",
          "nodes": [
            {
              "id": "github-call",
              "type": "http-request",
              "inputs": [
                {
                  "name": "apiKey",
                  "displayName": "GitHub API Key",
                  "data": {
                    "type": "string",
                    "source": "secret",
                    "sensitive": true,
                    "secretRef": {
                      "path": "services/github/api-key",
                      "key": "api_key",
                      "required": true,
                      "cacheTTL": 300,
                      "secretType": "api_key",
                      "validation": "size(value) >= 40"
                    }
                  }
                },
                {
                  "name": "endpoint",
                  "data": {
                    "type": "string",
                    "source": "input"
                  }
                }
              ]
            }
          ]
        }
        """;
    }
    
    public static String databaseExample() {
        return """
        {
          "id": "database-query",
          "name": "Database Query",
          "nodes": [
            {
              "id": "db-query",
              "type": "database-connector",
              "inputs": [
                {
                  "name": "connectionString",
                  "data": {
                    "type": "string",
                    "source": "secret",
                    "sensitive": true,
                    "secretRef": {
                      "path": "databases/production/connection",
                      "key": "connection_string",
                      "required": true,
                      "cacheTTL": 600,
                      "secretType": "password"
                    }
                  }
                }
              ]
            }
          ]
        }
        """;
    }
}

