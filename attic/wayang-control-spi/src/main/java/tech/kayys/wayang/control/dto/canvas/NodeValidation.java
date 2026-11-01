package tech.kayys.wayang.control.dto.canvas;

import java.util.List;
import java.util.ArrayList;
import lombok.Data;

/**
 * Validation state for a specific node.
 */
@Data
public class NodeValidation {
    public boolean isValid;
    public List<ValidationIssue> issues = new ArrayList<>();
}
