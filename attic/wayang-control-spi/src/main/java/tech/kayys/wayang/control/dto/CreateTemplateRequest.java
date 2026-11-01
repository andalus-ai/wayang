package tech.kayys.wayang.control.dto;

import java.util.List;

public record CreateTemplateRequest(
        String templateName,
        String description,
        String version,
        TemplateType templateType,
        String canvasDefinition, // Store as JSON string or ID in DTO
        List<String> tags) {
}