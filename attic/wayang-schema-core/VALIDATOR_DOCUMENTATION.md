# Enhanced Schema Validator for Wayang Platform

This document describes the enhanced schema validation system for the Wayang platform, which provides comprehensive validation capabilities for various data structures and plugin configurations.

## Overview

The enhanced schema validator provides:

- JSON Schema validation using the networknt/json-schema-validator library
- Custom validation rules for specific business logic
- Type-specific validators for agents, workflows, and plugins
- Integration with the plugin registry system
- Comprehensive validation utilities

## Architecture

### Core Components

1. **SchemaValidator** - Low-level JSON Schema validator
2. **SchemaValidationService** - Unified interface for validation operations
3. **SchemaValidationServiceImpl** - Implementation of the validation service
4. **ValidationResult** - Result of validation operations
5. **ValidationRule** - Defines custom validation rules
6. **Type-specific validators** - Specialized validators for different schema types

### Type-Specific Validators

- **AgentConfigValidator** - Validates agent configuration schemas
- **WorkflowValidator** - Validates workflow schemas
- **PluginConfigValidator** - Validates plugin configuration schemas
- **PluginValidationUtils** - Utilities for plugin validation

## Usage Examples

### Basic Schema Validation

```java
@Inject
SchemaValidator schemaValidator;

// Validate data against a JSON schema
String schema = "{ \"type\": \"object\", \"properties\": { \"name\": { \"type\": \"string\" } }, \"required\": [\"name\"] }";
Map<String, Object> data = Map.of("name", "test");
ValidationResult result = schemaValidator.validate(schema, data);

if (result.isValid()) {
    System.out.println("Validation passed");
} else {
    System.out.println("Validation failed: " + result.getMessage());
}
```

### Using the Unified Validation Service

```java
@Inject
SchemaValidationService validationService;

// Validate with custom rules
ValidationRule[] rules = {
    new ValidationRule("email", "pattern", "^[A-Za-z0-9+_.-]+@(.+)$", "Invalid email format", "ERROR", false)
};

Map<String, Object> data = Map.of("email", "test@example.com");
ValidationResult result = validationService.validateWithRules(rules, data);
```

### Validating Specific Types

```java
@Inject
AgentConfigValidator agentValidator;

// Validate agent configuration
Map<String, Object> agentConfig = Map.of(
    "model", "gpt-4",
    "temperature", 0.7,
    "maxTokens", 1000
);

ValidationResult result = agentValidator.validateAgentConfig(agentConfig);
```

## Validation Capabilities

### Built-in Validation Methods

The SchemaValidator provides several built-in validation methods:

- `validate()` - Validates against JSON Schema
- `validatePattern()` - Validates string against regex pattern
- `validateRange()` - Validates numeric values within range
- `validateStringLength()` - Validates string length
- `validateCollectionSize()` - Validates collection size
- `validateRequiredFields()` - Validates required fields

### Custom Validation Rules

Validation rules can be defined with the following types:

- `pattern` - Regular expression validation
- `range` - Numeric range validation (format: "min,max")
- `length` - String length validation (format: "min,max")
- `required` - Required field validation

## Plugin Configuration Validation

The validator includes specialized support for plugin configurations:

- Validates plugin manifest structure
- Checks platform compatibility
- Validates security constraints
- Supports multiple plugin validation

## Integration with Plugin Registry

The validation system is designed to work seamlessly with the plugin registry:

- Validates plugin configurations before registration
- Ensures compatibility with platform requirements
- Provides detailed error reporting for invalid plugins

## Best Practices

1. Always validate plugin configurations before loading
2. Use type-specific validators when available
3. Define custom validation rules for business logic
4. Handle validation errors gracefully
5. Log validation failures for debugging

## Error Handling

Validation errors are returned as ValidationResult objects with:

- `isValid()` - Indicates if validation passed
- `getMessage()` - Detailed error message for failures

## Extending the Validator

To add new validation capabilities:

1. Create a new type-specific validator class
2. Implement validation logic using the base SchemaValidator
3. Add custom ValidationRule types if needed
4. Register the validator as a CDI bean if needed

## Dependencies

The validator uses the following libraries:

- `com.networknt:json-schema-validator` - JSON Schema validation
- `com.fasterxml.jackson.core:jackson-databind` - JSON processing
- `org.jboss.logging:jboss-logging` - Logging