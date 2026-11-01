package tech.kayys.wayang.schema.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.HashMap;
import java.util.Map;

/**
 * Child workflow reference that allows composing a parent workflow from saved
 * projects/sub-workflows.
 */
public class ChildWorkflowSpec {

    @JsonProperty("childId")
    private String childId;

    @JsonProperty("projectId")
    private String projectId;

    @JsonProperty("definitionId")
    private String definitionId;

    @JsonProperty("metadata")
    private Map<String, Object> metadata = new HashMap<>();

    @JsonProperty("workflow")
    private WorkflowSpec workflow;

    public ChildWorkflowSpec() {
        // Default constructor for JSON deserialization
    }

    public String getChildId() {
        return childId;
    }

    public void setChildId(String childId) {
        this.childId = childId;
    }

    public String getProjectId() {
        return projectId;
    }

    public void setProjectId(String projectId) {
        this.projectId = projectId;
    }

    public String getDefinitionId() {
        return definitionId;
    }

    public void setDefinitionId(String definitionId) {
        this.definitionId = definitionId;
    }

    public Map<String, Object> getMetadata() {
        return metadata;
    }

    public void setMetadata(Map<String, Object> metadata) {
        this.metadata = metadata != null ? metadata : new HashMap<>();
    }

    public WorkflowSpec getWorkflow() {
        return workflow;
    }

    public void setWorkflow(WorkflowSpec workflow) {
        this.workflow = workflow;
    }
}
