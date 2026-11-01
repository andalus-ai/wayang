package tech.kayys.wayang.schema.canvas;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tech.kayys.wayang.schema.validator.ValidationIssue;

/**
 * Canvas validation result
 */
public class CanvasValidationResult {
    public boolean isValid;
    public List<ValidationIssue> errors = new ArrayList<>();
    public List<ValidationIssue> warnings = new ArrayList<>();
    public List<ValidationIssue> infos = new ArrayList<>();
    public Instant validatedAt;
    public String validatedBy;
    public Map<String, Object> metrics = new HashMap<>();
}