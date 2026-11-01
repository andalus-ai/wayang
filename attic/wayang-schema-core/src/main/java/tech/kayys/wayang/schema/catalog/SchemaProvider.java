package tech.kayys.wayang.schema.catalog;

import java.util.Map;

/**
 * SPI for modules (executors, plugins) to contribute their JSON Schemas
 * to the unified catalog at runtime.
 *
 * Implementations are discovered via {@link java.util.ServiceLoader}.
 * Each provider returns a map of schema-id → JSON Schema string.
 */
public interface SchemaProvider {

    /**
     * @return an immutable map of schema ID to generated JSON Schema (as a string).
     */
    Map<String, String> schemas();
}
