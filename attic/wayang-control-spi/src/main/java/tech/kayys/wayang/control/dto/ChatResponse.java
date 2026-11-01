package tech.kayys.wayang.control.dto;

import java.util.List;

public record ChatResponse(
                String response,
                boolean success,
                List<String> errors) {
}