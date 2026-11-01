package tech.kayys.wayang.schema.validator;

import java.util.Map;

/**
 * Unified interface for schema validation that can be used across the platform
 */
public interface SchemaValidationService {
    
    /**
     * Validates data against a JSON schema
     * 
     * @param schema The JSON schema to validate against
     * @param data The data to validate
     * @return ValidationResult indicating success or failure
     */
    ValidationResult validateSchema(String schema, Map<String, Object> data);
    
    /**
     * Validates data against a predefined schema reference
     * 
     * @param schemaRef Reference to the schema to use for validation
     * @param data The data to validate
     * @return ValidationResult indicating success or failure
     */
    ValidationResult validateSchema(SchemaReference schemaRef, Map<String, Object> data);
    
    /**
     * Validates data against a set of validation rules
     * 
     * @param rules The validation rules to apply
     * @param data The data to validate
     * @return ValidationResult indicating success or failure
     */
    ValidationResult validateWithRules(ValidationRule[] rules, Map<String, Object> data);
    
    /**
     * Performs comprehensive validation combining schema validation and custom rules
     * 
     * @param schema The JSON schema to validate against
     * @param rules Additional validation rules to apply
     * @param data The data to validate
     * @return ValidationResult indicating success or failure
     */
    ValidationResult validateComprehensive(String schema, ValidationRule[] rules, Map<String, Object> data);
}