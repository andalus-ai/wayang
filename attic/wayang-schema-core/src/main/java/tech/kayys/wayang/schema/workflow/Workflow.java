package tech.kayys.wayang.schema.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.kayys.wayang.schema.common.Metadata;
import tech.kayys.wayang.schema.node.Node;
import java.util.List;
import java.util.Map;

/**
 * Represents a workflow containing nodes and connections.
 */
public class Workflow {
    @JsonProperty("metadata")
    private Metadata metadata;

    @JsonProperty("nodes")
    private List<Node> nodes;

    @JsonProperty("connections")
    private List<Connection> connections;

    @JsonProperty("variables")
    private Map<String, Object> variables;

    @JsonProperty("configuration")
    private WorkflowConfig configuration;

    public Workflow() {
        // Default constructor for JSON deserialization
    }

    public Workflow(Metadata metadata, List<Node> nodes, List<Connection> connections, 
                   Map<String, Object> variables, WorkflowConfig configuration) {
        this.metadata = metadata;
        this.nodes = nodes;
        this.connections = connections;
        this.variables = variables;
        this.configuration = configuration;
    }

    // Getters
    public Metadata getMetadata() {
        return metadata;
    }

    public List<Node> getNodes() {
        return nodes;
    }

    public List<Connection> getConnections() {
        return connections;
    }

    public Map<String, Object> getVariables() {
        return variables;
    }

    public WorkflowConfig getConfiguration() {
        return configuration;
    }

    // Setters
    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    public void setNodes(List<Node> nodes) {
        this.nodes = nodes;
    }

    public void setConnections(List<Connection> connections) {
        this.connections = connections;
    }

    public void setVariables(Map<String, Object> variables) {
        this.variables = variables;
    }

    public void setConfiguration(WorkflowConfig configuration) {
        this.configuration = configuration;
    }
}