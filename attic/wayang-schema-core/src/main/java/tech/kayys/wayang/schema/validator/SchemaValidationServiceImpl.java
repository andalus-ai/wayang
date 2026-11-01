package tech.kayys.wayang.schema.validator;

import java.util.Map;
import java.util.List;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

/**
 * Implementation of the unified schema validation service
 */
@ApplicationScoped
public class SchemaValidationServiceImpl implements SchemaValidationService {
    
    @Inject
    private SchemaValidator schemaValidator;
    
    @Override
    public ValidationResult validateSchema(String schema, Map<String, Object> data) {
        return schemaValidator.validate(schema, data);
    }
    
    @Override
    public ValidationResult validateSchema(SchemaReference schemaRef, Map<String, Object> data) {
        // For now, just delegate to the string-based validation
        // In a real implementation, this would resolve the schema reference
        return schemaValidator.validate(schemaRef.getSchema(), data);
    }
    
    @Override
    public ValidationResult validateWithRules(ValidationRule[] rules, Map<String, Object> data) {
        if (rules == null || rules.length == 0) {
            return ValidationResult.success();
        }
        
        for (ValidationRule rule : rules) {
            ValidationResult result = applyValidationRule(rule, data);
            if (!result.isValid()) {
                return result;
            }
        }
        
        return ValidationResult.success();
    }
    
    @Override
    public ValidationResult validateComprehensive(String schema, ValidationRule[] rules, Map<String, Object> data) {
        // First validate against the schema
        ValidationResult schemaResult = validateSchema(schema, data);
        if (!schemaResult.isValid()) {
            return schemaResult;
        }
        
        // Then validate against the rules
        return validateWithRules(rules, data);
    }
    
    /**
     * Applies a single validation rule to the data
     */
    private ValidationResult applyValidationRule(ValidationRule rule, Map<String, Object> data) {
        String field = rule.getField();
        String ruleType = rule.getRuleType();
        String expression = rule.getExpression();
        String errorMessage = rule.getErrorMessage();
        
        if (field == null || !data.containsKey(field)) {
            // If the field doesn't exist and is required, return an error
            if (Boolean.TRUE.equals(rule.isRequired())) {
                return ValidationResult.failure("Required field '" + field + "' is missing");
            }
            // If the field doesn't exist and isn't required, skip validation
            return ValidationResult.success();
        }
        
        Object value = data.get(field);
        
        switch (ruleType.toLowerCase()) {
            case "pattern":
                return schemaValidator.validatePattern(value.toString(), expression);
            case "range":
                // Parse min/max values from expression (format: "min,max")
                String[] parts = expression.split(",");
                if (parts.length != 2) {
                    return ValidationResult.failure("Invalid range expression format: " + expression);
                }
                try {
                    Number min = Double.valueOf(parts[0].trim());
                    Number max = Double.valueOf(parts[1].trim());
                    if (value instanceof Number) {
                        return schemaValidator.validateRange((Number) value, min, max);
                    } else {
                        return ValidationResult.failure("Value is not a number: " + value);
                    }
                } catch (NumberFormatException e) {
                    return ValidationResult.failure("Invalid number format in range: " + expression);
                }
            case "length":
                // Parse min/max lengths from expression (format: "min,max")
                String[] lengthParts = expression.split(",");
                if (lengthParts.length != 2) {
                    return ValidationResult.failure("Invalid length expression format: " + expression);
                }
                try {
                    Integer minLength = Integer.valueOf(lengthParts[0].trim());
                    Integer maxLength = Integer.valueOf(lengthParts[1].trim());
                    if (value instanceof String) {
                        return schemaValidator.validateStringLength((String) value, minLength, maxLength);
                    } else {
                        return ValidationResult.failure("Value is not a string: " + value);
                    }
                } catch (NumberFormatException e) {
                    return ValidationResult.failure("Invalid number format in length: " + expression);
                }
            case "required":
                if (value == null) {
                    return ValidationResult.failure(errorMessage != null ? 
                        errorMessage : "Field '" + field + "' is required");
                }
                return ValidationResult.success();
            default:
                return ValidationResult.failure("Unknown validation rule type: " + ruleType);
        }
    }
}