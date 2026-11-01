package tech.kayys.wayang.schema.api.dto;

public class ValidationIssueDto {
    private String field;
    private String message;
    private String severity; // ERROR, WARNING, INFO

    public ValidationIssueDto() {
    }

    public ValidationIssueDto(String field, String message, String severity) {
        this.field = field;
        this.message = message;
        this.severity = severity;
    }

    public String getField() {
        return field;
    }

    public void setField(String field) {
        this.field = field;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public String getSeverity() {
        return severity;
    }

    public void setSeverity(String severity) {
        this.severity = severity;
    }
}