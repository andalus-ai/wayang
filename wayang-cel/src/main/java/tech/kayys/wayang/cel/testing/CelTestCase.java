package tech.kayys.wayang.cel.testing;

import java.util.Objects;

public record CelTestCase(String testCaseId, String artifactFingerprint,
        CelFixture fixture, CelTestExpectation expectation) {
    public CelTestCase {
        require(testCaseId, "testCaseId"); require(artifactFingerprint, "artifactFingerprint");
        Objects.requireNonNull(fixture, "fixture"); Objects.requireNonNull(expectation, "expectation");
    }
    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }
}
