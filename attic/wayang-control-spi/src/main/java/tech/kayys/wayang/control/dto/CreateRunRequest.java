package tech.kayys.wayang.control.dto;

import java.util.Map;

/**
 * Request to create a workflow run
 */
public record CreateRunRequest(
                String workflowDefinitionId,
                Map<String, Object> inputs,
                Map<String, String> metadata,
                String callbackUrl) {
}