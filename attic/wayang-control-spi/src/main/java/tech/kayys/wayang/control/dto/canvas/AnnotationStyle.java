package tech.kayys.wayang.control.dto.canvas;

import lombok.Data;

/**
 * Visual style for canvas annotations.
 */
@Data
public class AnnotationStyle {
    public String color;
    public String fontSize;
    public boolean bold;
    public boolean italic;
}
