package tech.kayys.wayang.control.dto.canvas;

import lombok.Data;
import java.time.Instant;

/**
 * Record of a specific change on the canvas.
 */
@Data
public class ChangeRecord {
    public String id;
    public String userId;
    public ChangeType type;
    public ChangeOperation operation;
    public String elementId;
    public String data; // JSON representation of change
    public Instant timestamp;
}
