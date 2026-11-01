package tech.kayys.wayang.control.dto.canvas;

import java.util.List;
import lombok.Data;
import lombok.NoArgsConstructor;

/**
 * A specific validation issue on the canvas.
 */
@Data
@NoArgsConstructor
public class ValidationIssue {
    public ValidationSeverity severity;
    public String code;
    public String message;
    public List<?> affectedFields;
    public String suggestedFix;

    public ValidationIssue(
            ValidationSeverity severity,
            String code,
            String message,
            List<?> affectedFields,
            String suggestedFix) {
        this.severity = severity;
        this.code = code;
        this.message = message;
        this.affectedFields = affectedFields;
        this.suggestedFix = suggestedFix;
    }
}
