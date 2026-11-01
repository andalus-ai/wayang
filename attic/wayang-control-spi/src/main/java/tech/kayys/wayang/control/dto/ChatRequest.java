package tech.kayys.wayang.control.dto;

import java.util.Map;

public record ChatRequest(
                String message,
                Map<String, Object> context) {
}