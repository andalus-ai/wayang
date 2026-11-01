package tech.kayys.wayang.control.dto;

/**
 * Validation issue specific to integration route design.
 */
public record DesignerValidationIssue(
        String severity,
        String code,
        String message,
        String elementId) {
}
