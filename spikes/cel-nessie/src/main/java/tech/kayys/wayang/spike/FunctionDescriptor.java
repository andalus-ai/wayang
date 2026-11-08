package tech.kayys.wayang.spike;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.HexFormat;
import java.util.List;
import java.util.Objects;
import java.util.regex.Pattern;

/**
 * Immutable, versioned metadata for one CEL function overload.
 *
 * <p>This descriptor is intentionally independent of CEL-Java classes so it can
 * become the stable Wayang contract if a different CEL provider is selected.
 */
public record FunctionDescriptor(
        String functionId,
        String functionName,
        String functionVersion,
        String overloadId,
        List<String> argumentTypes,
        String resultType,
        Effect effect,
        Determinism determinism,
        String implementationVersion) {

    private static final Pattern IDENTIFIER = Pattern.compile("[a-zA-Z][a-zA-Z0-9_.-]{0,127}");

    public FunctionDescriptor {
        functionId = requireIdentifier(functionId, "functionId");
        functionName = requireIdentifier(functionName, "functionName");
        functionVersion = requireVersion(functionVersion, "functionVersion");
        overloadId = requireIdentifier(overloadId, "overloadId");
        argumentTypes = List.copyOf(Objects.requireNonNull(argumentTypes, "argumentTypes"));
        argumentTypes.forEach(type -> requireIdentifier(type, "argumentType"));
        resultType = requireIdentifier(resultType, "resultType");
        effect = Objects.requireNonNull(effect, "effect");
        determinism = Objects.requireNonNull(determinism, "determinism");
        implementationVersion = requireVersion(implementationVersion, "implementationVersion");
    }

    public boolean expressionSafe() {
        return effect == Effect.PURE && determinism == Determinism.DETERMINISTIC;
    }

    /**
     * Stable SHA-256 over the semantic contract. Deliberately excludes implementation
     * class names, JVM identity, registration order, and mutable runtime state.
     */
    public String compatibilityFingerprint() {
        String canonical = String.join("|",
                functionId,
                functionName,
                functionVersion,
                overloadId,
                String.join(",", argumentTypes),
                resultType,
                effect.name(),
                determinism.name(),
                implementationVersion);
        try {
            return HexFormat.of().formatHex(
                    MessageDigest.getInstance("SHA-256")
                            .digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    private static String requireIdentifier(String value, String field) {
        Objects.requireNonNull(value, field);
        if (!IDENTIFIER.matcher(value).matches()) {
            throw new IllegalArgumentException(field + " is not a valid stable identifier: " + value);
        }
        return value;
    }

    private static String requireVersion(String value, String field) {
        Objects.requireNonNull(value, field);
        if (value.isBlank() || value.length() > 64 || !value.matches("[A-Za-z0-9][A-Za-z0-9.+_-]*")) {
            throw new IllegalArgumentException(field + " must be a non-empty version token");
        }
        return value;
    }

    public enum Effect {
        PURE,
        READ_ONLY,
        EXTERNAL_SIDE_EFFECT
    }

    public enum Determinism {
        DETERMINISTIC,
        CONTEXTUAL,
        NON_DETERMINISTIC
    }
}
