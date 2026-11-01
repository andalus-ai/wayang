package tech.kayys.wayang.project.dto;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.canvas.schema.CanvasEdge;
import tech.kayys.wayang.canvas.schema.CanvasNode;

/**
 * Canvas Definition - Visual workflow representation
 */
public class CanvasDefinition {
    public List<CanvasNode> nodes = new ArrayList<>();
    public List<CanvasEdge> edges = new ArrayList<>();
    public Map<String, Object> layout = new HashMap<>();
    public Map<String, Object> metadata = new HashMap<>();
}