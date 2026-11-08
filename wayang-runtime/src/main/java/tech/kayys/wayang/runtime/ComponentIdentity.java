package tech.kayys.wayang.runtime;

import java.util.Map;
import java.util.Objects;

/** Reserved, versioned workflow-node component binding metadata. */
public record ComponentIdentity(String id, String version, String operation) {
    public static final String ID_KEY = "wayang.component.id";
    public static final String VERSION_KEY = "wayang.component.version";
    public static final String OPERATION_KEY = "wayang.component.operation";

    public ComponentIdentity {
        id = requireText(id, "component id");
        if (!id.matches("[a-z][a-z0-9]*(?:[.-][a-z0-9]+)+"))
            throw new IllegalArgumentException("Invalid component id: " + id);
        version = optionalText(version, "component version");
        operation = operation == null || operation.isBlank() ? "execute" : operation.trim();
        if (operation.length() > 128)
            throw new IllegalArgumentException("Component operation must be at most 128 characters");
    }

    /** Returns null for legacy nodes; partial or malformed bindings are rejected. */
    public static ComponentIdentity fromConfiguration(Map<String, Object> configuration) {
        Objects.requireNonNull(configuration, "configuration");
        Object rawId = configuration.get(ID_KEY);
        Object rawVersion = configuration.get(VERSION_KEY);
        Object rawOperation = configuration.get(OPERATION_KEY);
        if (rawId == null) {
            if (rawVersion != null || rawOperation != null)
                throw new IllegalArgumentException("Component version/operation requires " + ID_KEY);
            return null;
        }
        if (!(rawId instanceof String id)) throw new IllegalArgumentException(ID_KEY + " must be a string");
        if (rawVersion != null && !(rawVersion instanceof String))
            throw new IllegalArgumentException(VERSION_KEY + " must be a string");
        if (rawOperation != null && !(rawOperation instanceof String))
            throw new IllegalArgumentException(OPERATION_KEY + " must be a string");
        return new ComponentIdentity(id, (String) rawVersion, (String) rawOperation);
    }

    private static String requireText(String value, String label) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(label + " must not be blank");
        return value.trim();
    }

    private static String optionalText(String value, String label) {
        return value == null || value.isBlank() ? null : requireText(value, label);
    }
}
