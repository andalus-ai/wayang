package tech.kayys.wayang.spike;

import java.util.Collection;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

/**
 * Immutable allowlist of expression-callable function overloads.
 *
 * <p>Registration is explicit and fails closed on duplicate function identities
 * or overload IDs. The registry is metadata-only; provider-specific declarations
 * and executable bindings are assembled by an adapter.
 */
public final class FunctionRegistry {

    private final Map<String, FunctionDescriptor> byOverloadId;
    private final String compatibilityFingerprint;

    private FunctionRegistry(Map<String, FunctionDescriptor> descriptors) {
        Map<String, FunctionDescriptor> ordered = new TreeMap<>(descriptors);
        this.byOverloadId = Collections.unmodifiableMap(new LinkedHashMap<>(ordered));
        this.compatibilityFingerprint = fingerprint(ordered.values());
    }

    public static Builder builder() {
        return new Builder();
    }

    public Collection<FunctionDescriptor> descriptors() {
        return byOverloadId.values();
    }

    public FunctionDescriptor require(String overloadId) {
        FunctionDescriptor descriptor = byOverloadId.get(overloadId);
        if (descriptor == null) {
            throw new IllegalArgumentException("Function overload is not allowlisted: " + overloadId);
        }
        return descriptor;
    }

    public String compatibilityFingerprint() {
        return compatibilityFingerprint;
    }

    public FunctionRegistry requireExpressionSafe() {
        for (FunctionDescriptor descriptor : byOverloadId.values()) {
            if (!descriptor.expressionSafe()) {
                throw new IllegalStateException(
                        "Function is not eligible for expression evaluation: " + descriptor.overloadId()
                                + " (effect=" + descriptor.effect()
                                + ", determinism=" + descriptor.determinism() + ")");
            }
        }
        return this;
    }

    private static String fingerprint(Collection<FunctionDescriptor> descriptors) {
        try {
            var digest = java.security.MessageDigest.getInstance("SHA-256");
            for (FunctionDescriptor descriptor : descriptors) {
                digest.update(descriptor.compatibilityFingerprint()
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8));
                digest.update((byte) '\n');
            }
            return java.util.HexFormat.of().formatHex(digest.digest());
        } catch (java.security.NoSuchAlgorithmException impossible) {
            throw new IllegalStateException("JVM does not provide SHA-256", impossible);
        }
    }

    public static final class Builder {
        private final Map<String, FunctionDescriptor> descriptors = new LinkedHashMap<>();
        private boolean built;

        public Builder register(FunctionDescriptor descriptor) {
            if (built) {
                throw new IllegalStateException("Function registry builder is already frozen");
            }
            Objects.requireNonNull(descriptor, "descriptor");
            if (descriptors.containsKey(descriptor.overloadId())) {
                throw new IllegalArgumentException("Duplicate CEL overload ID: " + descriptor.overloadId());
            }
            boolean duplicateFunctionVersion = descriptors.values().stream().anyMatch(existing ->
                    existing.functionId().equals(descriptor.functionId())
                            && existing.functionVersion().equals(descriptor.functionVersion())
                            && existing.overloadId().equals(descriptor.overloadId()));
            if (duplicateFunctionVersion) {
                throw new IllegalArgumentException("Duplicate function version and overload: "
                        + descriptor.functionId() + "/" + descriptor.functionVersion());
            }
            descriptors.put(descriptor.overloadId(), descriptor);
            return this;
        }

        public FunctionRegistry build() {
            if (built) {
                throw new IllegalStateException("Function registry builder can only be built once");
            }
            built = true;
            return new FunctionRegistry(descriptors);
        }
    }
}
