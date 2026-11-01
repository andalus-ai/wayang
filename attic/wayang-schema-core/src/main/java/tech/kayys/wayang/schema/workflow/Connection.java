package tech.kayys.wayang.schema.workflow;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a connection between two nodes in a workflow.
 */
public class Connection {
    @JsonProperty("fromNodeId")
    private String fromNodeId;

    @JsonProperty("toNodeId")
    private String toNodeId;

    @JsonProperty("fromPort")
    private String fromPort;

    @JsonProperty("toPort")
    private String toPort;

    @JsonProperty("condition")
    private String condition;

    public Connection() {
        // Default constructor for JSON deserialization
    }

    public Connection(String fromNodeId, String toNodeId, String fromPort, String toPort, String condition) {
        this.fromNodeId = fromNodeId;
        this.toNodeId = toNodeId;
        this.fromPort = fromPort;
        this.toPort = toPort;
        this.condition = condition;
    }

    // Getters
    public String getFromNodeId() {
        return fromNodeId;
    }

    public String getToNodeId() {
        return toNodeId;
    }

    public String getFromPort() {
        return fromPort;
    }

    public String getToPort() {
        return toPort;
    }

    public String getCondition() {
        return condition;
    }

    // Setters
    public void setFromNodeId(String fromNodeId) {
        this.fromNodeId = fromNodeId;
    }

    public void setToNodeId(String toNodeId) {
        this.toNodeId = toNodeId;
    }

    public void setFromPort(String fromPort) {
        this.fromPort = fromPort;
    }

    public void setToPort(String toPort) {
        this.toPort = toPort;
    }

    public void setCondition(String condition) {
        this.condition = condition;
    }
}