package tech.kayys.wayang.agent;

import java.util.Locale;
import java.util.Objects;
import java.util.regex.Pattern;

/** Detached signature envelope. Its fields are signed together with the exact archive digest. */
public record AgentPackageSignature(int formatVersion, String algorithm, String packageId, String version,
        String publisher, String keyId, String artifactSha256, String signatureBase64) {

    private static final Pattern DIGEST = Pattern.compile("(?i)[0-9a-f]{64}");
    private static final Pattern KEY_ID = Pattern.compile("[A-Za-z0-9][A-Za-z0-9._:-]{0,127}");

    public AgentPackageSignature {
        if (formatVersion != 1) throw new IllegalArgumentException("Unsupported agent package signature format");
        if (!"Ed25519".equals(algorithm)) throw new IllegalArgumentException("Only Ed25519 signatures are supported");
        require(packageId, "packageId");
        require(version, "version");
        require(publisher, "publisher");
        if (keyId == null || !KEY_ID.matcher(keyId).matches()) throw new IllegalArgumentException("Invalid publisher key ID");
        if (artifactSha256 == null || !DIGEST.matcher(artifactSha256).matches()) {
            throw new IllegalArgumentException("Artifact SHA-256 is required");
        }
        artifactSha256 = artifactSha256.toLowerCase(Locale.ROOT);
        Objects.requireNonNull(signatureBase64, "signatureBase64");
        if (signatureBase64.length() > 128) throw new IllegalArgumentException("Invalid Ed25519 signature encoding");
        try {
            byte[] decoded = java.util.Base64.getDecoder().decode(signatureBase64);
            if (decoded.length != 64) throw new IllegalArgumentException("Invalid Ed25519 signature length");
        } catch (IllegalArgumentException e) {
            throw new IllegalArgumentException("Invalid Ed25519 signature encoding", e);
        }
    }

    /** Stable domain-separated payload; do not change without introducing a new envelope format version. */
    public byte[] signedPayload() {
        String canonical = "wayang-agent-package-signature-v1\n"
                + packageId + "\n" + version + "\n" + publisher + "\n"
                + artifactSha256 + "\n" + keyId + "\n" + algorithm + "\n";
        return canonical.getBytes(java.nio.charset.StandardCharsets.UTF_8);
    }

    private static void require(String value, String name) {
        if (value == null || value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(name + " is required and must be at most 256 characters");
        }
    }
}
