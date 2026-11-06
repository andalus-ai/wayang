package tech.kayys.wayang.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;

/**
 * Runtime-neutral input to a component handler.
 *
 * <p>This contract deliberately contains no BPMN, Gamelan, DMN, or EIP types.
 * Domain adapters translate their native plan representation into this request.
 * The request is an immutable snapshot; nested values are expected to be
 * immutable or treated as read-only by handlers.</p>
 */
public record ExecutionRequest(
        String runId,
        String componentId,
        String componentVersion,
        String operation,
        Map<String, Object> inputs,
        Map<String, Object> configuration,
        ExecutionMetadata metadata) {

    public ExecutionRequest {
        runId = requireText(runId, "runId");
        componentId = requireText(componentId, "componentId");
        componentVersion = requireText(componentVersion, "componentVersion");
        operation = requireText(operation, "operation");
        inputs = immutableCopy(inputs, "inputs");
        configuration = immutableCopy(configuration, "configuration");
        metadata = Objects.requireNonNull(metadata, "metadata");
    }

    private static String requireText(String value, String name) {
        Objects.requireNonNull(value, name);
        if (value.isBlank()) throw new IllegalArgumentException(name + " must not be blank");
        return value;
    }

    private static Map<String, Object> immutableCopy(Map<String, Object> value, String name) {
        Objects.requireNonNull(value, name);
        return Collections.unmodifiableMap(new LinkedHashMap<>(value));
    }
}
