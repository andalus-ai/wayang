package tech.kayys.wayang.schema.validator;

import java.util.List;

public class ValidationIssue {
    public ValidationSeverity severity;
    public String code;
    public String message;
    public List<String> affectedFields;
    public String suggestedFix;

    public ValidationIssue() {
    }

    public ValidationIssue(ValidationSeverity severity, String code, String message, List<String> affectedFields,
            String suggestedFix) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.affectedFields = affectedFields;
        this.suggestedFix = suggestedFix;
    }
}
