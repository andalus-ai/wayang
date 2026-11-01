/*
 * PolyForm Noncommercial License 1.0.0
 *
 * Copyright (c) 2026 Kayys.tech
 *
 * This software is licensed for non-commercial use only.
 * You may use, modify, and distribute this software for personal,
 * educational, or research purposes.
 *
 * Commercial use, including SaaS or revenue-generating services,
 * requires a separate commercial license from Kayys.tech.
 *
 * THE SOFTWARE IS PROVIDED "AS IS", WITHOUT WARRANTY OF ANY KIND.
 *
 * @author Bhangun
 */

package tech.kayys.wayang.schema.validator;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import org.jboss.logging.Logger;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;
import com.networknt.schema.JsonSchemaFactory;
import com.networknt.schema.SpecVersion;
import com.networknt.schema.ValidationMessage;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.networknt.schema.JsonSchema;

/**
 * Runtime schema validator using JSON Schema
 */
@ApplicationScoped
public class SchemaValidator {

    private static final Logger LOG = Logger.getLogger(SchemaValidator.class);

    @Inject
    ObjectMapper objectMapper;

    private final JsonSchemaFactory schemaFactory = JsonSchemaFactory.getInstance(SpecVersion.VersionFlag.V7);

    /**
     * Validate data against JSON Schema
     */
    public ValidationResult validate(JsonSchema schema, Map<String, Object> data) {
        if (schema == null) {
            return ValidationResult.success();
        }

        try {
            JsonNode jsonNode = objectMapper.valueToTree(data);
            Set<ValidationMessage> errors = schema.validate(jsonNode);

            if (errors.isEmpty()) {
                return ValidationResult.success();
            } else {
                List<String> errorMessages = errors.stream()
                        .map(ValidationMessage::getMessage)
                        .toList();
                return ValidationResult.failure(String.join(", ", errorMessages));
            }
        } catch (Exception e) {
            LOG.errorf(e, "Schema validation error");
            return ValidationResult.failure("Validation error: " + e.getMessage());
        }
    }

    /**
     * Validate data against a schema definition string
     */
    public ValidationResult validate(String schemaJson, Map<String, Object> data) {
        try {
            JsonSchema schema = createSchema(schemaJson);
            return validate(schema, data);
        } catch (Exception e) {
            LOG.errorf(e, "Schema validation error");
            return ValidationResult.failure("Validation error: " + e.getMessage());
        }
    }

    /**
     * Create JSON Schema from schema string
     */
    public JsonSchema createSchema(String schemaJson) {
        try {
            JsonNode schemaNode = objectMapper.readTree(schemaJson);
            schemaNode = sanitizeSchemaNode(schemaNode);
            return schemaFactory.getSchema(schemaNode);
        } catch (Exception e) {
            LOG.errorf(e, "Failed to create schema");
            throw new RuntimeException("Invalid schema", e);
        }
    }

    private JsonNode sanitizeSchemaNode(JsonNode schemaNode) {
        if (schemaNode instanceof ObjectNode objectNode && objectNode.has("$schema")) {
            String declaredSchema = objectNode.path("$schema").asText("");
            // The bundled validator factory is configured for Draft-07.
            // If we receive newer meta-schema declarations, drop only the declaration
            // so validation can proceed using compatible keywords.
            if (declaredSchema.contains("json-schema.org/draft/2020-12")) {
                objectNode.remove("$schema");
            }
        }
        return schemaNode;
    }

    /**
     * Validate a string against a regular expression pattern
     */
    public ValidationResult validatePattern(String value, String pattern) {
        try {
            boolean matches = Pattern.matches(pattern, value);
            if (matches) {
                return ValidationResult.success();
            } else {
                return ValidationResult.failure("Value does not match pattern: " + pattern);
            }
        } catch (Exception e) {
            LOG.errorf(e, "Pattern validation error");
            return ValidationResult.failure("Pattern validation error: " + e.getMessage());
        }
    }

    /**
     * Validate that a value is within the specified range
     */
    public ValidationResult validateRange(Number value, Number min, Number max) {
        if (value == null) {
            return ValidationResult.failure("Value cannot be null");
        }

        if (min != null && value.doubleValue() < min.doubleValue()) {
            return ValidationResult.failure("Value " + value + " is less than minimum " + min);
        }

        if (max != null && value.doubleValue() > max.doubleValue()) {
            return ValidationResult.failure("Value " + value + " is greater than maximum " + max);
        }

        return ValidationResult.success();
    }

    /**
     * Validate that a string length is within the specified bounds
     */
    public ValidationResult validateStringLength(String value, Integer minLength, Integer maxLength) {
        if (value == null) {
            return ValidationResult.failure("Value cannot be null");
        }

        int length = value.length();

        if (minLength != null && length < minLength) {
            return ValidationResult.failure("String length " + length + " is less than minimum " + minLength);
        }

        if (maxLength != null && length > maxLength) {
            return ValidationResult.failure("String length " + length + " is greater than maximum " + maxLength);
        }

        return ValidationResult.success();
    }

    /**
     * Validate that a collection size is within the specified bounds
     */
    public <T> ValidationResult validateCollectionSize(java.util.Collection<T> collection, Integer minLength, Integer maxLength) {
        if (collection == null) {
            return ValidationResult.failure("Collection cannot be null");
        }

        int size = collection.size();

        if (minLength != null && size < minLength) {
            return ValidationResult.failure("Collection size " + size + " is less than minimum " + minLength);
        }

        if (maxLength != null && size > maxLength) {
            return ValidationResult.failure("Collection size " + size + " is greater than maximum " + maxLength);
        }

        return ValidationResult.success();
    }

    /**
     * Validate required fields in a map
     */
    public ValidationResult validateRequiredFields(Map<String, Object> data, List<String> requiredFields) {
        for (String field : requiredFields) {
            if (!data.containsKey(field) || data.get(field) == null) {
                return ValidationResult.failure("Required field missing: " + field);
            }
        }
        return ValidationResult.success();
    }
}
