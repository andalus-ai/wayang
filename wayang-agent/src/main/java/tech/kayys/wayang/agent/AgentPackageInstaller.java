package tech.kayys.wayang.agent;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;

/** Validates project installation inputs and produces an immutable installed-agent record and lock. */
public final class AgentPackageInstaller {

    private final AgentDependencyResolver resolver;

    public AgentPackageInstaller() {
        this(new AgentDependencyResolver());
    }

    public AgentPackageInstaller(AgentDependencyResolver resolver) {
        this.resolver = Objects.requireNonNull(resolver, "resolver");
    }

    public InstalledAgent install(String tenantId, String projectId, String installationId,
            AgentDependencyResolver.PackageArtifact root,
            List<AgentDependencyResolver.PackageArtifact> available,
            Map<String, InstalledAgent.ConfigurationBinding> bindings, Instant now) {
        Objects.requireNonNull(root, "root");
        Objects.requireNonNull(now, "now");
        Map<String, InstalledAgent.ConfigurationBinding> safeBindings = new LinkedHashMap<>(
                bindings == null ? Map.of() : bindings);
        Map<String, AgentPackageReader.ConfigurationRequirement> requirements = new LinkedHashMap<>();
        for (AgentPackageReader.ConfigurationRequirement requirement : root.agentPackage().configuration()) {
            requirements.put(requirement.id(), requirement);
        }
        for (String supplied : safeBindings.keySet()) {
            if (!requirements.containsKey(supplied)) {
                throw new InstallationException("Unknown configuration binding '" + supplied + "'");
            }
        }
        for (AgentPackageReader.ConfigurationRequirement requirement : requirements.values()) {
            if (requirement.required() && !safeBindings.containsKey(requirement.id())) {
                throw new InstallationException("Required configuration reference is missing: " + requirement.id());
            }
            InstalledAgent.ConfigurationBinding binding = safeBindings.get(requirement.id());
            if (binding != null && !binding.kind().equals(requirement.kind())) {
                throw new InstallationException("Configuration binding kind mismatch for '" + requirement.id() + "'");
            }
        }
        AgentPackageLock lock = resolver.resolve(root, available).lock();
        return new InstalledAgent(tenantId, projectId, installationId, lock, safeBindings,
                root.agentPackage().requestedPermissions(), now, now);
    }

    public static final class InstallationException extends IllegalArgumentException {
        public InstallationException(String message) { super(message); }
    }
}
