package tech.kayys.wayang.schema.data;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a field in a data schema.
 */
public class Field {
    @JsonProperty("name")
    private String name;

    @JsonProperty("type")
    private String type;

    @JsonProperty("nullable")
    private boolean nullable;

    @JsonProperty("defaultValue")
    private Object defaultValue;

    @JsonProperty("constraints")
    private FieldConstraints constraints;

    public Field() {
        // Default constructor for JSON deserialization
    }

    public Field(String name, String type, boolean nullable, Object defaultValue, FieldConstraints constraints) {
        this.name = name;
        this.type = type;
        this.nullable = nullable;
        this.defaultValue = defaultValue;
        this.constraints = constraints;
    }

    // Getters
    public String getName() {
        return name;
    }

    public String getType() {
        return type;
    }

    public boolean isNullable() {
        return nullable;
    }

    public Object getDefaultValue() {
        return defaultValue;
    }

    public FieldConstraints getConstraints() {
        return constraints;
    }

    // Setters
    public void setName(String name) {
        this.name = name;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setNullable(boolean nullable) {
        this.nullable = nullable;
    }

    public void setDefaultValue(Object defaultValue) {
        this.defaultValue = defaultValue;
    }

    public void setConstraints(FieldConstraints constraints) {
        this.constraints = constraints;
    }
}