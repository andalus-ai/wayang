package tech.kayys.wayang.control.spi;

import io.smallrye.mutiny.Uni;
import tech.kayys.wayang.schema.validator.ValidationResult;

import java.util.Map;

/**
 * SPI interface for schema registry services.
 */
public interface SchemaRegistrySpi {
    
    /**
     * Register a schema with the registry.
     */
    Uni<Void> registerSchema(String schemaId, String schema, String schemaType, Map<String, String> metadata);
    
    /**
     * Get a schema by ID.
     */
    Uni<String> getSchema(String schemaId);
    
    /**
     * Validate data against a registered schema.
     */
    Uni<ValidationResult> validateAgainstSchema(String schemaId, Map<String, Object> data);
    
    /**
     * Validate data against a schema definition.
     */
    Uni<ValidationResult> validateSchema(String schema, Map<String, Object> data);
    
    /**
     * Remove a schema from the registry.
     */
    Uni<Void> removeSchema(String schemaId);
}