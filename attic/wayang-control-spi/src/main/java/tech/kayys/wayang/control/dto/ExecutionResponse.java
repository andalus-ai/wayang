package tech.kayys.wayang.control.dto;

import java.time.Instant;

public record ExecutionResponse(
                String runId,
                String status,
                Instant startedAt) {
}
