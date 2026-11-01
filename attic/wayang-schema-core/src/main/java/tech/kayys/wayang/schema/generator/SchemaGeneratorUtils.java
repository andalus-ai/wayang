package tech.kayys.wayang.schema.generator;

import com.fasterxml.jackson.databind.JsonNode;
import com.github.victools.jsonschema.generator.OptionPreset;
import com.github.victools.jsonschema.generator.SchemaGenerator;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfig;
import com.github.victools.jsonschema.generator.SchemaGeneratorConfigBuilder;
import com.github.victools.jsonschema.generator.SchemaVersion;
import com.github.victools.jsonschema.module.jackson.JacksonModule;
import com.github.victools.jsonschema.module.jackson.JacksonOption;

public class SchemaGeneratorUtils {

    private static final SchemaGenerator GENERATOR;

    static {
        // Use Draft 2020-12
        SchemaGeneratorConfigBuilder configBuilder = new SchemaGeneratorConfigBuilder(
                SchemaVersion.DRAFT_2020_12, OptionPreset.PLAIN_JSON);

        // Register Jackson Module to respect @JsonProperty annotations
        JacksonModule module = new JacksonModule(JacksonOption.RESPECT_JSONPROPERTY_REQUIRED);
        configBuilder.with(module);

        SchemaGeneratorConfig config = configBuilder.build();
        GENERATOR = new SchemaGenerator(config);
    }

    private SchemaGeneratorUtils() {
    }

    /**
     * Generates a JSON Schema for the given Java class type.
     * 
     * @param targetType the class to generate a schema for
     * @return the generated JSON Schema as a formatted string
     */
    public static String generateSchema(Class<?> targetType) {
        JsonNode jsonSchema = GENERATOR.generateSchema(targetType);
        return jsonSchema.toPrettyString();
    }
}
