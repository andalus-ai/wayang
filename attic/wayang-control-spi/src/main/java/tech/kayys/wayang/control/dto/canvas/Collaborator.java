package tech.kayys.wayang.control.dto.canvas;

import lombok.Data;

/**
 * Represents a collaborator on a canvas.
 */
@Data
public class Collaborator {
    public String userId;
    public String name;
    public String email;
    public CollaboratorRole role;
    public long joinedAt;
}
