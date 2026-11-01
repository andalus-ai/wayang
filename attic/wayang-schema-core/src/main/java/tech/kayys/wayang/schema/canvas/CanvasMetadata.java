package tech.kayys.wayang.schema.canvas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tech.kayys.wayang.schema.collaboration.Collaborator;
import tech.kayys.wayang.schema.data.Comment;

/**
 * Canvas metadata
 */
public class CanvasMetadata {
    public Map<String, String> labels = new HashMap<>();
    public String category;
    public List<String> keywords = new ArrayList<>();
    public String iconUrl;
    public String thumbnailUrl;
    public double complexity;
    public int estimatedExecutionTime; // seconds
    public Map<String, Object> customFields = new HashMap<>();

    // Collaboration
    public List<Collaborator> collaborators = new ArrayList<>();
    public List<Comment> comments = new ArrayList<>();
}
