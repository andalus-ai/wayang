package tech.kayys.wayang.schema.validator;

import java.util.Map;
import java.util.List;
import java.util.ArrayList;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Utility class for validating plugin configurations
 */
@ApplicationScoped
public class PluginValidationUtils {
    
    @Inject
    private SchemaValidationService validationService;
    
    @Inject
    private PluginConfigValidator pluginConfigValidator;
    
    /**
     * Validates a plugin manifest structure
     */
    public ValidationResult validatePluginManifest(Map<String, Object> manifest) {
        ValidationRule[] rules = {
            new ValidationRule("pluginId", "required", null, "Plugin ID is required", "ERROR", true),
            new ValidationRule("pluginName", "required", null, "Plugin name is required", "ERROR", true),
            new ValidationRule("version", "required", null, "Plugin version is required", "ERROR", true),
            new ValidationRule("provider", "required", null, "Plugin provider is required", "ERROR", true)
        };
        
        String schema = "{\n" +
                "  \"type\": \"object\",\n" +
                "  \"properties\": {\n" +
                "    \"pluginId\": { \"type\": \"string\" },\n" +
                "    \"pluginName\": { \"type\": \"string\" },\n" +
                "    \"version\": { \"type\": \"string\" },\n" +
                "    \"provider\": { \"type\": \"string\" },\n" +
                "    \"description\": { \"type\": \"string\" },\n" +
                "    \"category\": { \"type\": \"string\" },\n" +
                "    \"tags\": { \n" +
                "      \"type\": \"array\",\n" +
                "      \"items\": { \"type\": \"string\" }\n" +
                "    },\n" +
                "    \"dependencies\": { \n" +
                "      \"type\": \"array\",\n" +
                "      \"items\": { \"type\": \"string\" }\n" +
                "    },\n" +
                "    \"configuration\": { \"type\": \"object\" }\n" +
                "  },\n" +
                "  \"required\": [\"pluginId\", \"pluginName\", \"version\", \"provider\"]\n" +
                "}";
        
        return validationService.validateComprehensive(schema, rules, manifest);
    }
    
    /**
     * Validates multiple plugin configurations
     */
    public List<ValidationResult> validatePluginConfigs(List<Map<String, Object>> configs) {
        List<ValidationResult> results = new ArrayList<>();
        
        for (Map<String, Object> config : configs) {
            results.add(pluginConfigValidator.validatePluginConfig(config));
        }
        
        return results;
    }
    
    /**
     * Checks if a plugin configuration is compatible with the current platform version
     */
    public ValidationResult validatePlatformCompatibility(Map<String, Object> pluginConfig, String platformVersion) {
        // Extract required platform version from plugin config
        Object requiredVersionObj = pluginConfig.get("requiredPlatformVersion");
        if (requiredVersionObj == null) {
            // If no specific version required, assume compatible
            return ValidationResult.success();
        }
        
        String requiredVersion = requiredVersionObj.toString();
        
        // Simple version comparison (in a real implementation, this would be more sophisticated)
        if (platformVersion.startsWith(requiredVersion.substring(0, Math.min(requiredVersion.length(), platformVersion.length())))) {
            return ValidationResult.success();
        } else {
            return ValidationResult.failure("Plugin requires platform version " + requiredVersion + 
                                          " but current version is " + platformVersion);
        }
    }
    
    /**
     * Validates plugin security constraints
     */
    public ValidationResult validateSecurityConstraints(Map<String, Object> pluginConfig) {
        ValidationRule[] rules = {
            new ValidationRule("permissions", "required", null, "Plugin permissions must be explicitly defined", "ERROR", false)
        };
        
        return validationService.validateWithRules(rules, pluginConfig);
    }
}