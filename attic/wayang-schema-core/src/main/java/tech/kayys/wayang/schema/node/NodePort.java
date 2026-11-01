package tech.kayys.wayang.schema.node;

import com.fasterxml.jackson.annotation.JsonProperty;

/**
 * Represents a port on a node.
 */
public class NodePort {
    @JsonProperty("id")
    private String id;

    @JsonProperty("name")
    private String name;

    @JsonProperty("type")
    private PortType type;

    public NodePort() {
    }

    public NodePort(String id, String name, PortType type) {
        this.id = id;
        this.name = name;
        this.type = type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getName() {
        return name;
    }

    public void setName(String name) {
        this.name = name;
    }

    public PortType getType() {
        return type;
    }

    public void setType(PortType type) {
        this.type = type;
    }
}
