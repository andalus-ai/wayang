package tech.kayys.wayang.schema.canvas;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import tech.kayys.wayang.schema.node.NodePort;

/**
 * Canvas data structure
 */
public class CanvasData {
    public List<CanvasNode> nodes = new ArrayList<>();
    public List<CanvasEdge> edges = new ArrayList<>();
    public CanvasLayout layout = new CanvasLayout();
    public Map<String, Object> variables = new HashMap<>();
    public List<CanvasGroup> groups = new ArrayList<>();
    public List<CanvasAnnotation> annotations = new ArrayList<>();
}