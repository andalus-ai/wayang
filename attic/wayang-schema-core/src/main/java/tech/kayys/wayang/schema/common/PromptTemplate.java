package tech.kayys.wayang.schema.common;

import com.fasterxml.jackson.annotation.JsonProperty;
import java.util.List;
import java.util.Map;

/**
 * Represents a template for prompts used by AI models.
 */
public class PromptTemplate {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("template")
    private String template;

    @JsonProperty("variables")
    private List<String> variables;

    @JsonProperty("description")
    private String description;

    @JsonProperty("tags")
    private List<String> tags;

    @JsonProperty("metadata")
    private Map<String, Object> metadata;

    @JsonProperty("version")
    private String version;

    public PromptTemplate() {
        // Default constructor for JSON deserialization
    }

    public PromptTemplate(String id, String name, String template, List<String> variables,
                         String description, List<String> tags, Map<String, Object> metadata, String version) {
        this.id = id;
        this.name = name;
        this.template = template;
        this.variables = variables;
        this.description = description;
        this.tags = tags;
        this.metadata = metadata;
        this.version = version;
    }

    // Getters
    public String getId() {
        return id;
    }

    public String getName() {
        return name;
    }

    public String getTemplate() {
        return template;
    }

    public List<String> getVariables() {
        return variables;
    }

    public String getDescription() {
        return description;
    }

    public List<String> getTags() {
        return tags;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public String getVersion() {
        return version;
    }

    // Setters
    public void setId(String id) {
        this.id = id;
    }

    public void setName(String name) {
        this.name = name;
    }

    public void setTemplate(String template) {
        this.template = template;
    }

    public void setVariables(List<String> variables) {
        this.variables = variables;
    }

    public void setDescription(String description) {
        this.description = description;
    }

    public void setTags(List<String> tags) {
        this.tags = tags;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata;
    }

    public void setVersion(String version) {
        this.version = version;
    }
}