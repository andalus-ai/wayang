package tech.kayys.wayang.schema.node;

import com.fasterxml.jackson.annotation.JsonProperty;
import tech.kayys.wayang.schema.common.Metadata;
import tech.kayys.wayang.schema.common.Position;
import java.util.Map;

/**
 * Represents a node in a workflow.
 */
public class Node {
    @JsonProperty("metadata")
    private Metadata metadata;

    @JsonProperty("type")
    private String type;

    @JsonProperty("position")
    private Position position;

    @JsonProperty("inputs")
    private Map<String, Object> inputs;

    @JsonProperty("outputs")
    private Map<String, Object> outputs;

    @JsonProperty("configuration")
    private Map<String, Object> configuration;

    @JsonProperty("status")
    private String status;

    public Node() {
        // Default constructor for JSON deserialization
    }

    public Node(Metadata metadata, String type, Position position, Map<String, Object> inputs, 
                Map<String, Object> outputs, Map<String, Object> configuration, String status) {
        this.metadata = metadata;
        this.type = type;
        this.position = position;
        this.inputs = inputs;
        this.outputs = outputs;
        this.configuration = configuration;
        this.status = status;
    }

    // Getters
    public Metadata getMetadata() {
        return metadata;
    }

    public String getType() {
        return type;
    }

    public Position getPosition() {
        return position;
    }

    public Map<String, Object> getInputs() {
        return inputs;
    }

    public Map<String, Object> getOutputs() {
        return outputs;
    }

    public Map<String, Object> getConfiguration() {
        return configuration;
    }

    public String getStatus() {
        return status;
    }

    // Setters
    public void setMetadata(Metadata metadata) {
        this.metadata = metadata;
    }

    public void setType(String type) {
        this.type = type;
    }

    public void setPosition(Position position) {
        this.position = position;
    }

    public void setInputs(Map<String, Object> inputs) {
        this.inputs = inputs;
    }

    public void setOutputs(Map<String, Object> outputs) {
        this.outputs = outputs;
    }

    public void setConfiguration(Map<String, Object> configuration) {
        this.configuration = configuration;
    }

    public void setStatus(String status) {
        this.status = status;
    }
}