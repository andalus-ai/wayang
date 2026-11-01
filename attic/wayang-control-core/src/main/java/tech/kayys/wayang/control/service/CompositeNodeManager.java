package tech.kayys.wayang.control.service;

import jakarta.enterprise.context.ApplicationScoped;
import tech.kayys.wayang.schema.canvas.CanvasData;
import tech.kayys.wayang.schema.canvas.CanvasNode;
import tech.kayys.wayang.control.dto.node.NodePort;
import tech.kayys.wayang.control.dto.node.PortDirection;

import java.util.ArrayList;
import java.util.List;

/**
 * Service for managing composite nodes and recursive composition.
 * Discovers interface ports from sub-workflow canvas definitions.
 */
@ApplicationScoped
public class CompositeNodeManager {

    /**
     * Interface node types used to define sub-workflow boundaries.
     */
    public static final String TYPE_INPUT_INTERFACE = "wayang:system:input";
    public static final String TYPE_OUTPUT_INTERFACE = "wayang:system:output";

    /**
     * Discovers external ports for a composite node based on its internal
     * sub-workflow canvas.
     */
    public List<NodePort> discoverPorts(CanvasData canvasData) {
        List<NodePort> ports = new ArrayList<>();
        if (canvasData == null || canvasData.nodes == null) {
            return ports;
        }

        for (CanvasNode node : canvasData.nodes) {
            if (TYPE_INPUT_INTERFACE.equals(node.type)) {
                ports.add(mapToExternalPort(node, PortDirection.INPUT));
            } else if (TYPE_OUTPUT_INTERFACE.equals(node.type)) {
                ports.add(mapToExternalPort(node, PortDirection.OUTPUT));
            }
        }

        return ports;
    }

    private NodePort mapToExternalPort(CanvasNode interfaceNode, PortDirection direction) {
        // We use the node's label and ID to create an external port
        // Configuration in the interface node could define data types, validation, etc.
        String dataType = (String) interfaceNode.config.getOrDefault("dataType", "any");

        NodePort port = new NodePort();
        port.id = interfaceNode.id; // Use the interface node's ID as the port ID for consistency
        port.direction = direction;
        port.label = interfaceNode.label;
        port.dataType = dataType;
        port.relativePosition = null; // Position is handled by the parent node's layout
        return port;
    }
}
