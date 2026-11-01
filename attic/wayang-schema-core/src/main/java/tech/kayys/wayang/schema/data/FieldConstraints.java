package tech.kayys.wayang.schema.data;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents constraints for a field in a data schema.
 */
public class FieldConstraints {
    @JsonProperty("minLength")
    private Integer minLength;

    @JsonProperty("maxLength")
    private Integer maxLength;

    @JsonProperty("minValue")
    private Number minValue;

    @JsonProperty("maxValue")
    private Number maxValue;

    @JsonProperty("pattern")
    private String pattern;

    @JsonProperty("enumValues")
    private String[] enumValues;

    public FieldConstraints() {
        // Default constructor for JSON deserialization
    }

    public FieldConstraints(Integer minLength, Integer maxLength, Number minValue, Number maxValue,
                          String pattern, String[] enumValues) {
        this.minLength = minLength;
        this.maxLength = maxLength;
        this.minValue = minValue;
        this.maxValue = maxValue;
        this.pattern = pattern;
        this.enumValues = enumValues;
    }

    // Getters
    public Integer getMinLength() {
        return minLength;
    }

    public Integer getMaxLength() {
        return maxLength;
    }

    public Number getMinValue() {
        return minValue;
    }

    public Number getMaxValue() {
        return maxValue;
    }

    public String getPattern() {
        return pattern;
    }

    public String[] getEnumValues() {
        return enumValues;
    }

    // Setters
    public void setMinLength(Integer minLength) {
        this.minLength = minLength;
    }

    public void setMaxLength(Integer maxLength) {
        this.maxLength = maxLength;
    }

    public void setMinValue(Number minValue) {
        this.minValue = minValue;
    }

    public void setMaxValue(Number maxValue) {
        this.maxValue = maxValue;
    }

    public void setPattern(String pattern) {
        this.pattern = pattern;
    }

    public void setEnumValues(String[] enumValues) {
        this.enumValues = enumValues;
    }
}