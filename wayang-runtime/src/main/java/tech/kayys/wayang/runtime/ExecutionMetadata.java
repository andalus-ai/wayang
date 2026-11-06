package tech.kayys.wayang.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/** Non-domain execution metadata passed through runtime component boundaries. */
public record ExecutionMetadata(
        String tenantId,
        String environment,
        String correlationId,
        String idempotencyKey,
        Map<String, String> attributes) {

    public ExecutionMetadata {
        attributes = Collections.unmodifiableMap(new LinkedHashMap<>(
                Objects.requireNonNull(attributes, "attributes")));
    }

    public static ExecutionMetadata empty() {
        return new ExecutionMetadata(null, null, null, null, Map.of());
    }
}
