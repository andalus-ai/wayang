package tech.kayys.wayang.spike;

import java.time.Instant;
import java.util.Objects;

/**
 * Trusted host metadata for one evaluation admission request.
 *
 * <p>tenantId must be resolved from authenticated server-side identity. It
 * must never be copied from expression variables or an untrusted request body.
 */
public record EvaluationRequestContext(
        String tenantId,
        String correlationId,
        Instant deadline,
        EvaluationCancellationToken cancellationToken) {

    public EvaluationRequestContext {
        tenantId = requireText(tenantId, "tenantId");
        correlationId = requireText(correlationId, "correlationId");
        cancellationToken = Objects.requireNonNull(cancellationToken, "cancellationToken");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) {
            throw new IllegalArgumentException(name + " must not be blank");
        }
        return value;
    }
}
