package tech.kayys.wayang.deployment;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.Map;
import java.util.TreeMap;

/** Canonical SHA-256 fingerprint; parameter order does not affect command identity. */
public final class DeploymentCommandFingerprint {
    private DeploymentCommandFingerprint() {}

    public static String of(String operation, DeploymentCommand command) {
        StringBuilder canonical = new StringBuilder();
        append(canonical, operation);
        append(canonical, command.deploymentId());
        append(canonical, command.tenantId());
        append(canonical, command.environment());
        append(canonical, command.targetId());
        append(canonical, command.releaseId());
        new TreeMap<>(command.parameters()).forEach((key, value) -> {
            append(canonical, key);
            append(canonical, value);
        });
        try {
            return java.util.HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256").digest(canonical.toString().getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("SHA-256 is required by the Java runtime", impossible);
        }
    }

    private static void append(StringBuilder target, String value) {
        String safe = value == null ? "" : value;
        target.append(safe.length()).append(':').append(safe).append(';');
    }
}
