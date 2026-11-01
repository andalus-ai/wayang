package tech.kayys.wayang.schema.api.dto;

import java.util.List;

public class SchemaValidationResponse {
    private boolean valid;
    private String message;
    private List<ValidationIssueDto> issues;

    public SchemaValidationResponse() {
    }

    public SchemaValidationResponse(boolean valid, String message, List<ValidationIssueDto> issues) {
        this.valid = valid;
        this.message = message;
        this.issues = issues;
    }

    public static SchemaValidationResponse success() {
        return new SchemaValidationResponse(true, null, null);
    }

    public static SchemaValidationResponse failure(String message) {
        return new SchemaValidationResponse(false, message, null);
    }

    public boolean isValid() {
        return valid;
    }

    public void setValid(boolean valid) {
        this.valid = valid;
    }

    public String getMessage() {
        return message;
    }

    public void setMessage(String message) {
        this.message = message;
    }

    public List<ValidationIssueDto> getIssues() {
        return issues;
    }

    public void setIssues(List<ValidationIssueDto> issues) {
        this.issues = issues;
    }
}