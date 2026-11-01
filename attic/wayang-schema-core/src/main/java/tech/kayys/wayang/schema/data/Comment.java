package tech.kayys.wayang.schema.data;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

public class Comment {
    public String id;
    public String userId;
    public String text;
    public String nodeId; // Optional: comment on specific node
    public Instant createdAt;
    public List<String> replies = new ArrayList<>();
}