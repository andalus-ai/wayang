package tech.kayys.wayang.cel.testing;

import java.util.Map;
import java.util.Objects;
import tech.kayys.wayang.cel.CelValue;

/** Synthetic typed inputs for preview or deterministic tests. */
public record CelFixture(String fixtureId, String scopeFingerprint, Map<String, CelValue> variables) {
    public CelFixture {
        require(fixtureId, "fixtureId"); require(scopeFingerprint, "scopeFingerprint");
        variables = Map.copyOf(Objects.requireNonNull(variables, "variables"));
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
