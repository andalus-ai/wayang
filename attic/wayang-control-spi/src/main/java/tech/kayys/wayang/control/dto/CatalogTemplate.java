package tech.kayys.wayang.control.dto;

import java.util.List;

import tech.kayys.wayang.schema.canvas.CanvasData;

public record CatalogTemplate(
        String id,
        String name,
        String description,
        String category,
        TemplateType templateType,
        CanvasData canvasData,
        List<String> tags,
        String difficulty) {
}
