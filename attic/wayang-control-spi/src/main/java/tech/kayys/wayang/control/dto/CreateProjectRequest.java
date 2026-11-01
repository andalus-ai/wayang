package tech.kayys.wayang.control.dto;

import java.util.Map;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;

/**
 * Request to create a new project
 */
public record CreateProjectRequest(
        @NotBlank
        String tenantId,
        
        @NotBlank
        String projectName,
        
        String description,
        
        @NotNull
        ProjectType projectType,
        
        String createdBy,
        
        Map<String, Object> metadata) {
}