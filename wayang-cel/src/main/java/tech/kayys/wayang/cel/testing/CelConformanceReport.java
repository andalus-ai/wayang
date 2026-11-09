package tech.kayys.wayang.cel.testing;

import java.util.List;
import java.util.Objects;

/** Immutable outcome of running one provider against a pinned conformance suite. */
public record CelConformanceReport(String suiteId, String providerId, String providerVersion,
        List<CaseResult> cases) {
    public CelConformanceReport {
        require(suiteId, "suiteId");
        require(providerId, "providerId");
        require(providerVersion, "providerVersion");
        cases = List.copyOf(Objects.requireNonNull(cases, "cases"));
    }

    public long passedCount() {
        return cases.stream().filter(CaseResult::passed).count();
    }

    public long failedCount() {
        return cases.size() - passedCount();
    }

    public boolean successful() {
        return !cases.isEmpty() && failedCount() == 0;
    }

    private static void require(String value, String field) {
        if (value == null || value.isBlank()) throw new IllegalArgumentException(field + " is required");
    }

    public record CaseResult(String testCaseId, boolean passed, String actualStatus,
            List<String> diagnosticCodes) {
        public CaseResult {
            require(testCaseId, "testCaseId");
            require(actualStatus, "actualStatus");
            diagnosticCodes = List.copyOf(Objects.requireNonNull(diagnosticCodes, "diagnosticCodes"));
        }
    }
}
