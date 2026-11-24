package tech.kayys.wayang.agent;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;

/** Immutable, deterministic lock for the exact artifacts selected for an agent installation. */
public record AgentPackageLock(String packageId, String version, String artifactSha256,
        List<LockedDependency> dependencies, String sha256) {

    public AgentPackageLock {
        requireText(packageId, "packageId");
        requireText(version, "version");
        requireDigest(artifactSha256, "artifactSha256");
        dependencies = List.copyOf(dependencies == null ? List.of() : dependencies);
        String expected = digest(packageId, version, artifactSha256, dependencies);
        if (sha256 == null || sha256.isBlank()) {
            sha256 = expected;
        } else if (!expected.equalsIgnoreCase(sha256)) {
            throw new IllegalArgumentException("Agent package lock digest does not match its contents");
        }
    }

    public static AgentPackageLock create(String packageId, String version, String artifactSha256,
            List<LockedDependency> dependencies) {
        return new AgentPackageLock(packageId, version, artifactSha256, dependencies, null);
    }

    private static String digest(String packageId, String version, String artifactSha256,
            List<LockedDependency> dependencies) {
        StringBuilder canonical = new StringBuilder("wayang.agent-package-lock\n")
                .append(packageId).append('\n').append(version).append('\n')
                .append(artifactSha256.toLowerCase(java.util.Locale.ROOT)).append('\n');
        dependencies.stream().sorted().forEach(d -> canonical.append(d.kind()).append('|')
                .append(d.packageId()).append('|').append(d.version()).append('|')
                .append(d.artifactSha256().toLowerCase(java.util.Locale.ROOT)).append('\n'));
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }

    private static void requireText(String value, String name) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(name + " is required");
    }

    private static void requireDigest(String value, String name) {
        if (value == null || !value.matches("(?i)[0-9a-f]{64}"))
            throw new IllegalArgumentException(name + " must be a SHA-256 hex digest");
    }

    public record LockedDependency(String kind, String packageId, String version, String artifactSha256)
            implements Comparable<LockedDependency> {
        public LockedDependency {
            requireText(kind, "dependency.kind");
            requireText(packageId, "dependency.packageId");
            requireText(version, "dependency.version");
            requireDigest(artifactSha256, "dependency.artifactSha256");
        }

        @Override
        public int compareTo(LockedDependency other) {
            int kindOrder = kind.compareTo(other.kind);
            if (kindOrder != 0) return kindOrder;
            int idOrder = packageId.compareTo(other.packageId);
            if (idOrder != 0) return idOrder;
            return version.compareTo(other.version);
        }
    }
}
