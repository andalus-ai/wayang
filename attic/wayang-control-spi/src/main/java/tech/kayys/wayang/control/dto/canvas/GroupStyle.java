package tech.kayys.wayang.control.dto.canvas;

import lombok.Data;

/**
 * Visual style of a group.
 */
@Data
public class GroupStyle {
    public String backgroundColor;
    public String borderColor;
    public double borderWidth;
    public double borderRadius;
}
