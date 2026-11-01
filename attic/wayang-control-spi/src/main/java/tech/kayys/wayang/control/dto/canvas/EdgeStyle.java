package tech.kayys.wayang.control.dto.canvas;

import lombok.Data;

/**
 * Visual style of an edge.
 */
@Data
public class EdgeStyle {
    public String color;
    public double strokeWidth;
    public String dashArray;
    public String markerEnd;
    public String markerStart;
}
