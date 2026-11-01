package tech.kayys.wayang.schema.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.time.Instant;
import java.util.Map;

/**
 * Represents metadata associated with various entities in the system.
 */
public class Metadata {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("description")
    private String description;

    @JsonProperty("createdAt")
    private Instant createdAt;

    @JsonProperty("updatedAt")
    private Instant updatedAt;

    @JsonProperty("version")
    private String version;

    @JsonProperty("tags")
    private Map<String, String> tags;

    @JsonProperty("labels")
    private Map<String, String> labels;

    public Metadata() {
        // Default constructor for JSON deserialization
    }

    public Metadata(String id, String name, String description, Instant createdAt, 
                   Instant updatedAt, String version, Map<String, String> tags, 
                   Map<String, String> labels) {
        this.id = id;
        this.name = name;
        this.description = description;
        this.createdAt = createdAt;
        this.updatedAt = updatedAt;
        this.version = version;
        this.tags = tags;
        this.labels = labels;
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getDescription() {
        return description;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public String getVersion() {
        return version;
    }

    public Map<String, String> getTags() {
        return tags;
    }

    public Map<String, String> getLabels() {
        return labels;
    }

    // Setters
    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setCreatedAt(Instant createdAt) {
        this.createdAt = createdAt;
    }

    public void setUpdatedAt(Instant updatedAt) {
        this.updatedAt = updatedAt;
    }

    public void setVersion(String version) {
        this.version = version;
    }

    public void setTags(Map<String, String> tags) {
        this.tags = tags;
    }

    public void setLabels(Map<String, String> labels) {
        this.labels = labels;
    }
}