package tech.kayys.wayang.schema.canvas;

import java.util.ArrayList;
import java.util.List;
import tech.kayys.wayang.schema.common.Position;
import tech.kayys.wayang.schema.layout.Dimensions;
import tech.kayys.wayang.schema.layout.GroupStyle;

/**
 * Node grouping/swimlanes
 */
public class CanvasGroup {
    public String id;
    public String label;
    public List<String> nodeIds = new ArrayList<>();
    public Position position;
    public Dimensions dimensions;
    public GroupStyle style = new GroupStyle();
}