package tech.kayys.wayang.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** A project-scoped installation record. It is not a deployed or running agent service. */
public record InstalledAgent(String tenantId, String projectId, String installationId,
        AgentPackageLock lock, Map<String, ConfigurationBinding> configurationBindings,
        List<String> requestedPermissions, Instant installedAt, Instant updatedAt,
        VerificationReceipt verification) {

    /** Source-compatible constructor for local/trusted-catalog installations without a publisher receipt. */
    public InstalledAgent(String tenantId, String projectId, String installationId,
            AgentPackageLock lock, Map<String, ConfigurationBinding> configurationBindings,
            List<String> requestedPermissions, Instant installedAt, Instant updatedAt) {
        this(tenantId, projectId, installationId, lock, configurationBindings, requestedPermissions,
                installedAt, updatedAt, null);
    }

    public InstalledAgent {
        require(tenantId, "tenantId", 128);
        require(projectId, "projectId", 128);
        require(installationId, "installationId", 128);
        Objects.requireNonNull(lock, "lock");
        Map<String, ConfigurationBinding> copy = new LinkedHashMap<>();
        if (configurationBindings != null) configurationBindings.forEach((key, value) -> {
            require(key, "configuration binding id", 128);
            copy.put(key, Objects.requireNonNull(value, "configuration binding"));
        });
        configurationBindings = Map.copyOf(copy);
        requestedPermissions = List.copyOf(requestedPermissions == null ? List.of() : requestedPermissions);
        Objects.requireNonNull(installedAt, "installedAt");
        Objects.requireNonNull(updatedAt, "updatedAt");
        if (updatedAt.isBefore(installedAt)) throw new IllegalArgumentException("updatedAt precedes installedAt");
        if (verification != null && verification.verifiedAt().isAfter(updatedAt)) {
            throw new IllegalArgumentException("Verification receipt timestamp cannot follow the installation update");
        }
    }

    private static void require(String value, String name, int maxLength) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
        if (value.length() > maxLength) throw new IllegalArgumentException(name + " exceeds " + maxLength + " characters");
    }

    /** Audit metadata for the exact verified package; it is not a reusable trust token. */
    public record VerificationReceipt(String publisher, String keyId, String signingKeyFingerprint,
            String manifestSha256, Instant verifiedAt) {
        public VerificationReceipt {
            require(publisher, "verification.publisher", 256);
            require(keyId, "verification.keyId", 128);
            requireDigest(signingKeyFingerprint, "verification.signingKeyFingerprint");
            requireDigest(manifestSha256, "verification.manifestSha256");
            Objects.requireNonNull(verifiedAt, "verifiedAt");
        }
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}")) {
            throw new IllegalArgumentException(name + " must be a SHA-256 digest");
        }
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
