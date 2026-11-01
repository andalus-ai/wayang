package tech.kayys.wayang.control.dto;

import java.util.List;
import java.util.Map;

public record IntegrationExecutionResult(
                boolean success,
                Object transformedPayload,
                Map<String, Object> metadata,
                List<String> errors) {
}
