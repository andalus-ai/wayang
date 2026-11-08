package tech.kayys.wayang.runtime;

import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.Map;
import java.util.Objects;
import java.util.Set;

/**
 * Deployment and invocation capabilities declared for one exact component version.
 * This metadata does not authorize execution, load plugin code, or choose a worker.
 */
public record ComponentExecutionCapabilities(
        String componentId,
        String componentVersion,
        Set<ComponentExecutionMode> supportedModes,
        ComponentStateModel stateModel,
        boolean portablePayloads,
        boolean supportsCancellation,
        boolean idempotent,
        String affinityKey,
        Set<String> requiredCapabilities,
        Map<String, String> resourceHints) {

    public ComponentExecutionCapabilities {
        componentId = requireText(componentId, "componentId");
        if (!componentId.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+")) {
            throw new IllegalArgumentException("componentId must be a namespaced lowercase id: " + componentId);
        }
        componentVersion = requireText(componentVersion, "componentVersion");
        Objects.requireNonNull(supportedModes, "supportedModes");
        if (supportedModes.isEmpty() || supportedModes.contains(null)) {
            throw new IllegalArgumentException("supportedModes must contain at least one non-null mode");
        }
        supportedModes = Collections.unmodifiableSet(new LinkedHashSet<>(supportedModes));
        stateModel = Objects.requireNonNull(stateModel, "stateModel");
        affinityKey = affinityKey == null || affinityKey.isBlank() ? null : affinityKey.trim();
        if (stateModel == ComponentStateModel.AFFINE && affinityKey == null) {
            throw new IllegalArgumentException("AFFINE components must declare an affinityKey");
        }
        if (stateModel != ComponentStateModel.AFFINE && affinityKey != null) {
            throw new IllegalArgumentException("affinityKey is only valid for AFFINE components");
        }
        boolean remote = supportedModes.contains(ComponentExecutionMode.REMOTE)
                || supportedModes.contains(ComponentExecutionMode.CLOUD_NATIVE);
        if (remote && !portablePayloads) {
            throw new IllegalArgumentException("REMOTE and CLOUD_NATIVE modes require portablePayloads");
        }
        requiredCapabilities = requiredCapabilities == null
                ? Set.of() : Collections.unmodifiableSet(new LinkedHashSet<>(requiredCapabilities));
        if (requiredCapabilities.contains(null)
                || requiredCapabilities.stream().anyMatch(value -> value.isBlank())) {
            throw new IllegalArgumentException("requiredCapabilities must not contain blank values");
        }
        Map<String, String> hints = new LinkedHashMap<>();
        if (resourceHints != null) {
            resourceHints.forEach((key, value) -> hints.put(
                    requireText(key, "resourceHints key"), requireText(value, "resourceHints value")));
        }
        resourceHints = Collections.unmodifiableMap(hints);
    }

    public boolean supportsRemoteExecution() {
        return supportedModes.contains(ComponentExecutionMode.REMOTE)
                || supportedModes.contains(ComponentExecutionMode.CLOUD_NATIVE);
    }

    public boolean requiresAffinity() {
        return stateModel == ComponentStateModel.AFFINE;
    }

    private static String requireText(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
        return value.trim();
    }
}
