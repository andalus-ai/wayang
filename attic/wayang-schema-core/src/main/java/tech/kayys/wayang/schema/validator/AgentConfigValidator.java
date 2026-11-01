package tech.kayys.wayang.schema.validator;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.wayang.schema.catalog.BuiltinSchemaCatalog;

/**
 * Validator for agent configuration schemas
 */
@ApplicationScoped
public class AgentConfigValidator {
    
    @Inject
    private SchemaValidationService validationService;
    
    /**
     * Validates an agent configuration
     */
    public ValidationResult validateAgentConfig(Map<String, Object> config) {
        // Define validation rules specific to agent configurations
        ValidationRule[] rules = {
            new ValidationRule("model", "required", null, "Model is required for agent configuration", "ERROR", true),
            new ValidationRule("temperature", "range", "0,1", "Temperature must be between 0 and 1", "WARNING", false),
            new ValidationRule("maxTokens", "range", "1,100000", "Max tokens must be between 1 and 100000", "WARNING", false),
            new ValidationRule("topP", "range", "0,1", "TopP must be between 0 and 1", "WARNING", false)
        };
        
        String schema = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.AGENT_CONFIG);
        
        return validationService.validateComprehensive(schema, rules, config);
    }
}
