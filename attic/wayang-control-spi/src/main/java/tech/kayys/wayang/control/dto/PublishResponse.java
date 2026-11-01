package tech.kayys.wayang.control.dto;

public record PublishResponse(
                boolean success,
                String workflowDefinitionId,
                String message) {
}
