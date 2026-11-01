package tech.kayys.wayang.control.dto.node;

import java.util.Map;

import tech.kayys.wayang.schema.node.PortType;
import tech.kayys.wayang.schema.layout.Position;

/**
 * 
 * Node port definition
 */
public class NodePort {
    public String id;
    public PortDirection direction;
    public String label;
    public String dataType;
    public boolean required;
    public String description;
    public Map<String, Object> metadata;

    public PortType type; // INPUT, OUTPUT, BIDIRECTIONAL

    public Position relativePosition;

    public static NodePort input(String id, String label, String dataType) {
        NodePort port = new NodePort();
        port.id = id;
        port.direction = PortDirection.INPUT;
        port.label = label;
        port.dataType = dataType;
        port.required = false;
        return port;
    }

    public static NodePort output(String id, String label, String dataType) {
        NodePort port = new NodePort();
        port.id = id;
        port.direction = PortDirection.OUTPUT;
        port.label = label;
        port.dataType = dataType;
        return port;
    }
}
