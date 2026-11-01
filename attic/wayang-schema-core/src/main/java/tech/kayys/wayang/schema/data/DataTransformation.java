package tech.kayys.wayang.schema.data;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.Map;

/**
 * Represents a data transformation operation.
 */
public class DataTransformation {
    @JsonProperty("type")
    private String type;

    @JsonProperty("sourceField")
    private String sourceField;

    @JsonProperty("targetField")
    private String targetField;

    @JsonProperty("expression")
    private String expression;

    @JsonProperty("configuration")
    private Map<String, Object> configuration;

    public DataTransformation() {
        // Default constructor for JSON deserialization
    }

    public DataTransformation(String type, String sourceField, String targetField, String expression,
                             Map<String, Object> configuration) {
        this.type = type;
        this.sourceField = sourceField;
        this.targetField = targetField;
        this.expression = expression;
        this.configuration = configuration;
    }

    // Getters
    public String getType() {
        return type;
    }

    public String getSourceField() {
        return sourceField;
    }

    public String getTargetField() {
        return targetField;
    }

    public String getExpression() {
        return expression;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    // Setters
    public void setType(String type) {
        this.type = type;
    }

    public void setSourceField(String sourceField) {
        this.sourceField = sourceField;
    }

    public void setTargetField(String targetField) {
        this.targetField = targetField;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
    }
}