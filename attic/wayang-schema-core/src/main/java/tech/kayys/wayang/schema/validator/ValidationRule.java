package tech.kayys.wayang.schema.validator;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a validation rule for data.
 */
public class ValidationRule {
    @JsonProperty("field")
    private String field;

    @JsonProperty("ruleType")
    private String ruleType;

    @JsonProperty("expression")
    private String expression;

    @JsonProperty("errorMessage")
    private String errorMessage;

    @JsonProperty("severity")
    private String severity;

    @JsonProperty("required")
    private Boolean required;

    public ValidationRule() {
        // Default constructor for JSON deserialization
    }

    public ValidationRule(String field, String ruleType, String expression, String errorMessage, String severity, Boolean required) {
        this.field = field;
        this.ruleType = ruleType;
        this.expression = expression;
        this.errorMessage = errorMessage;
        this.severity = severity;
        this.required = required;
    }

    // Getters
    public String getField() {
        return field;
    }

    public String getRuleType() {
        return ruleType;
    }

    public String getExpression() {
        return expression;
    }

    public String getErrorMessage() {
        return errorMessage;
    }

    public String getSeverity() {
        return severity;
    }

    public Boolean isRequired() {
        return required != null ? required : false; // Default to false if not specified
    }

    // Setters
    public void setField(String field) {
        this.field = field;
    }

    public void setRuleType(String ruleType) {
        this.ruleType = ruleType;
    }

    public void setExpression(String expression) {
        this.expression = expression;
    }

    public void setErrorMessage(String errorMessage) {
        this.errorMessage = errorMessage;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }

    public void setRequired(Boolean required) {
        this.required = required;
    }
}