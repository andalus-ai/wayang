package tech.kayys.wayang.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A project-scoped installation record. It is not a deployed or running agent service. */
public record InstalledAgent(String tenantId, String projectId, String installationId,
        AgentPackageLock lock, Map<String, ConfigurationBinding> configurationBindings,
        List<String> requestedPermissions, Instant installedAt, Instant updatedAt) {

    public InstalledAgent {
        require(tenantId, "tenantId");
        require(projectId, "projectId");
        require(installationId, "installationId");
        Objects.requireNonNull(lock, "lock");
        Map<String, ConfigurationBinding> copy = new LinkedHashMap<>();
        if (configurationBindings != null) configurationBindings.forEach((key, value) -> {
            require(key, "configuration binding id");
            copy.put(key, Objects.requireNonNull(value, "configuration binding"));
        });
        configurationBindings = Map.copyOf(copy);
        requestedPermissions = List.copyOf(requestedPermissions == null ? List.of() : requestedPermissions);
        Objects.requireNonNull(installedAt, "installedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(installedAt)) throw new IllegalArgumentException("updatedAt precedes installedAt");
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    /** Only references are persisted; secret values and connection credentials never belong here. */
    public record ConfigurationBinding(String kind, String reference) {
        public ConfigurationBinding {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(reference, "reference");
            if ("secret-reference".equals(kind) && !reference.startsWith("secret://"))
                throw new IllegalArgumentException("Secret configuration must use a secret:// reference");
            if ("connection-reference".equals(kind) && !reference.startsWith("connection://"))
                throw new IllegalArgumentException("Connection configuration must use a connection:// reference");
            if ("value".equals(kind) && !reference.startsWith("config://"))
                throw new IllegalArgumentException("Value configuration must use a config:// reference");
            if (!List.of("secret-reference", "connection-reference", "value").contains(kind))
                throw new IllegalArgumentException("Configuration must be an external config, secret, or connection reference");
            if (reference.length() > 512 || reference.contains("\n") || reference.contains("\r"))
                throw new IllegalArgumentException("Invalid configuration reference");
        }
    }
}
