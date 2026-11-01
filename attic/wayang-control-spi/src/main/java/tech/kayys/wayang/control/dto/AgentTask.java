package tech.kayys.wayang.control.dto;

import java.util.Map;

/**
 * Request to execute a task on an AI Agent.
 */
public record AgentTask(
        String taskId,
        String instruction,
        Map<String, Object> context) {
}
