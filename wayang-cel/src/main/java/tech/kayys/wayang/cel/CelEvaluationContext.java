package tech.kayys.wayang.cel;

import java.util.Map;
import java.util.Objects;

/** Typed variables only; trusted tenant and principal context is supplied server-side. */
public record CelEvaluationContext(String contextId, String environmentFingerprint,
        String scopeFingerprint, Map<String, CelValue> variables) {
    public CelEvaluationContext {
        requireText(contextId, "contextId");
        requireText(environmentFingerprint, "environmentFingerprint");
        requireText(scopeFingerprint, "scopeFingerprint");
        variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
    }
    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " must not be blank");
    }
}
