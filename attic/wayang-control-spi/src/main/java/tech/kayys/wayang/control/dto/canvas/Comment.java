package tech.kayys.wayang.control.dto.canvas;

import lombok.Data;
import java.time.Instant;
import tech.kayys.wayang.schema.common.Position;

/**
 * A comment on the canvas.
 */
@Data
public class Comment {
    public String id;
    public String userId;
    public String text;
    public Position position;
    public Instant createdAt;
    public Instant updatedAt;
}
