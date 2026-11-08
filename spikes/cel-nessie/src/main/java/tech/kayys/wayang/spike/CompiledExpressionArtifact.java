package tech.kayys.wayang.spike;

import java.io.ByteArrayOutputStream;
import java.io.DataOutputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.Objects;

/**
 * Immutable compatibility envelope for a compiled expression.
 *
 * <p>This is metadata for a durable artifact format; it does not serialize the
 * provider's executable Program/Ast and therefore is not, by itself, a recovery
 * mechanism. A runtime must rehydrate only after exact compatibility validation.
 */
public record CompiledExpressionArtifact(
        int schemaVersion,
        String source,
        String sourceSha256,
        String expectedResultType,
        String semanticProfile,
        String providerId,
        String providerVersion,
        String functionRegistryFingerprint,
        String environmentFingerprint,
        String variableSchemaFingerprint,
        String compileOptionsFingerprint,
        String policyVersion,
        String artifactFingerprint) {

    public static final int CURRENT_SCHEMA_VERSION = 1;

    public CompiledExpressionArtifact {
        if (schemaVersion != CURRENT_SCHEMA_VERSION) {
            throw new IllegalArgumentException("Unsupported artifact schema version: " + schemaVersion);
        }
        source = Objects.requireNonNull(source, "source");
        sourceSha256 = requireFingerprint(sourceSha256, "sourceSha256");
        expectedResultType = requireToken(expectedResultType, "expectedResultType");
        semanticProfile = requireToken(semanticProfile, "semanticProfile");
        providerId = requireToken(providerId, "providerId");
        providerVersion = requireToken(providerVersion, "providerVersion");
        functionRegistryFingerprint = requireFingerprint(functionRegistryFingerprint,
                "functionRegistryFingerprint");
        environmentFingerprint = requireFingerprint(environmentFingerprint, "environmentFingerprint");
        variableSchemaFingerprint = requireFingerprint(variableSchemaFingerprint,
                "variableSchemaFingerprint");
        compileOptionsFingerprint = requireFingerprint(compileOptionsFingerprint,
                "compileOptionsFingerprint");
        policyVersion = requireToken(policyVersion, "policyVersion");
        artifactFingerprint = requireFingerprint(artifactFingerprint, "artifactFingerprint");
        if (!sha256(source).equals(sourceSha256)) {
            throw new IllegalArgumentException("Source digest does not match artifact source");
        }
        if (!fingerprint(schemaVersion, source, expectedResultType, semanticProfile, providerId,
                providerVersion, functionRegistryFingerprint, environmentFingerprint,
                variableSchemaFingerprint, compileOptionsFingerprint, policyVersion)
                .equals(artifactFingerprint)) {
            throw new IllegalArgumentException("Artifact fingerprint does not match artifact metadata");
        }
    }

    public static CompiledExpressionArtifact create(
            String source,
            String expectedResultType,
            String semanticProfile,
            String providerId,
            String providerVersion,
            String functionRegistryFingerprint,
            String environmentFingerprint,
            String variableSchemaFingerprint,
            String compileOptionsFingerprint,
            String policyVersion) {
        Objects.requireNonNull(source, "source");
        String sourceDigest = sha256(source);
        String artifactDigest = fingerprint(CURRENT_SCHEMA_VERSION, source, expectedResultType,
                semanticProfile, providerId, providerVersion, functionRegistryFingerprint,
                environmentFingerprint, variableSchemaFingerprint, compileOptionsFingerprint,
                policyVersion);
        return new CompiledExpressionArtifact(CURRENT_SCHEMA_VERSION, source, sourceDigest,
                expectedResultType, semanticProfile, providerId, providerVersion,
                functionRegistryFingerprint, environmentFingerprint, variableSchemaFingerprint,
                compileOptionsFingerprint, policyVersion, artifactDigest);
    }

    public void requireCompatible(Compatibility target) {
        Objects.requireNonNull(target, "target");
        requireEqual("schemaVersion", schemaVersion, target.schemaVersion());
        requireEqual("expectedResultType", expectedResultType, target.expectedResultType());
        requireEqual("semanticProfile", semanticProfile, target.semanticProfile());
        requireEqual("providerId", providerId, target.providerId());
        requireEqual("providerVersion", providerVersion, target.providerVersion());
        requireEqual("functionRegistryFingerprint", functionRegistryFingerprint,
                target.functionRegistryFingerprint());
        requireEqual("environmentFingerprint", environmentFingerprint, target.environmentFingerprint());
        requireEqual("variableSchemaFingerprint", variableSchemaFingerprint,
                target.variableSchemaFingerprint());
        requireEqual("compileOptionsFingerprint", compileOptionsFingerprint,
                target.compileOptionsFingerprint());
        requireEqual("policyVersion", policyVersion, target.policyVersion());
    }

    private static void requireEqual(String field, Object artifact, Object target) {
        if (!Objects.equals(artifact, target)) {
            throw new IncompatibleExpressionArtifactException(field, String.valueOf(artifact),
                    String.valueOf(target));
        }
    }

    private static String fingerprint(
            int schemaVersion, String source, String expectedResultType, String semanticProfile,
            String providerId, String providerVersion, String registry, String environment,
            String variableSchema, String compileOptions, String policyVersion) {
        try {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            try (DataOutputStream out = new DataOutputStream(bytes)) {
                out.writeInt(schemaVersion);
                write(out, source);
                write(out, expectedResultType);
                write(out, semanticProfile);
                write(out, providerId);
                write(out, providerVersion);
                write(out, registry);
                write(out, environment);
                write(out, variableSchema);
                write(out, compileOptions);
                write(out, policyVersion);
            }
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes.toByteArray()));
        } catch (IOException | NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("Unable to fingerprint expression artifact", impossible);
        }
    }

    private static void write(DataOutputStream out, String value) throws IOException {
        byte[] encoded = Objects.requireNonNull(value, "artifact field").getBytes(StandardCharsets.UTF_8);
        out.writeInt(encoded.length);
        out.write(encoded);
    }

    private static String sha256(String value) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256")
                    .digest(value.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private static String requireToken(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 256) {
            throw new IllegalArgumentException(field + " must be a non-blank token of at most 256 characters");
        }
        return value;
    }

    private static String requireFingerprint(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!value.matches("[a-fA-F0-9]{64}")) {
            throw new IllegalArgumentException(field + " must be a 64-character SHA-256 hex digest");
        }
        return value.toLowerCase(java.util.Locale.ROOT);
    }

    public record Compatibility(
            int schemaVersion,
            String expectedResultType,
            String semanticProfile,
            String providerId,
            String providerVersion,
            String functionRegistryFingerprint,
            String environmentFingerprint,
            String variableSchemaFingerprint,
            String compileOptionsFingerprint,
            String policyVersion) {
    }
}
