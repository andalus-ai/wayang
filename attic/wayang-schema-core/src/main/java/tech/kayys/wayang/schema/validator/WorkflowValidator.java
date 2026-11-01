package tech.kayys.wayang.schema.validator;

import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import tech.kayys.wayang.schema.catalog.BuiltinSchemaCatalog;

/**
 * Validator for workflow schemas
 */
@ApplicationScoped
public class WorkflowValidator {
    
    @Inject
    private SchemaValidationService validationService;
    
    /**
     * Validates a workflow configuration
     */
    public ValidationResult validateWorkflow(Map<String, Object> workflow) {
        // Define validation rules specific to workflow configurations
        ValidationRule[] rules = {
            new ValidationRule("name", "required", null, "Workflow name is required", "ERROR", true),
            new ValidationRule("name", "length", "1,100", "Workflow name must be between 1 and 100 characters", "ERROR", false),
            new ValidationRule("nodes", "required", null, "Workflow must have at least one node", "ERROR", true)
        };
        
        String schema = BuiltinSchemaCatalog.get(BuiltinSchemaCatalog.WORKFLOW_SPEC);
        
        return validationService.validateComprehensive(schema, rules, workflow);
    }
}
