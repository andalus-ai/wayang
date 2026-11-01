package tech.kayys.wayang.schema.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.kayys.wayang.schema.common.Metadata;
import tech.kayys.wayang.schema.validator.ValidationRule;
import java.util.List;
import java.util.Map;

/**
 * Represents data processing configuration and schemas.
 */
public class DataSchema {
    @JsonProperty("metadata")
    private Metadata metadata;

    @JsonProperty("fields")
    private List<Field> fields;

    @JsonProperty("source")
    private DataSource source;

    @JsonProperty("transformations")
    private List<DataTransformation> transformations;

    @JsonProperty("validationRules")
    private List<ValidationRule> validationRules;

    @JsonProperty("configuration")
    private Map<String, Object> configuration;

    public DataSchema() {
        // Default constructor for JSON deserialization
    }

    public DataSchema(Metadata metadata, List<Field> fields, DataSource source,
            List<DataTransformation> transformations, List<ValidationRule> validationRules,
            Map<String, Object> configuration) {
        this.metadata = metadata;
        this.fields = fields;
        this.source = source;
        this.transformations = transformations;
        this.validationRules = validationRules;
        this.configuration = configuration;
    }

    // Getters
    public Metadata getMetadata() {
        return metadata;
    }

    public List<Field> getFields() {
        return fields;
    }

    public DataSource getSource() {
        return source;
    }

    public List<DataTransformation> getTransformations() {
        return transformations;
    }

    public List<ValidationRule> getValidationRules() {
        return validationRules;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    // Setters
    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    public void setFields(List<Field> fields) {
        this.fields = fields;
    }

    public void setSource(DataSource source) {
        this.source = source;
    }

    public void setTransformations(List<DataTransformation> transformations) {
        this.transformations = transformations;
    }

    public void setValidationRules(List<ValidationRule> validationRules) {
        this.validationRules = validationRules;
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
    }
}