package tech.kayys.wayang.cel.testing;

import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import tech.kayys.wayang.cel.CelCompileResult;
import tech.kayys.wayang.cel.CelCompilerProvider;
import tech.kayys.wayang.cel.CompiledCelExpression;

/**
 * Exercises compilation against a single provider and verifies artifact provenance.
 *
 * <p>The runner intentionally lets provider exceptions escape. A provider that
 * throws outside the compile-result contract is defective and must fail CI.</p>
 */
public final class CelCompilationConformanceRunner {

    public CelConformanceReport run(String suiteId, CelCompilerProvider compiler,
            List<CelCompileTestCase> testCases) {
        requireText(suiteId, "suiteId");
        Objects.requireNonNull(compiler, "compiler");
        Objects.requireNonNull(testCases, "testCases");

        Set<String> ids = new HashSet<>();
        var results = testCases.stream().map(testCase -> {
            Objects.requireNonNull(testCase, "testCase");
            if (!ids.add(testCase.testCaseId())) {
                throw new IllegalArgumentException("Duplicate compiler test case id: " + testCase.testCaseId());
            }

            CelCompileResult actual = Objects.requireNonNull(
                    compiler.compile(testCase.request()), "compiler returned null result");
            if (actual.successful() != testCase.expectedSuccessful()) {
                throw new IllegalStateException("Compiler conformance mismatch for "
                        + testCase.testCaseId() + ": expected successful="
                        + testCase.expectedSuccessful() + " but was " + actual.successful());
            }

            if (actual.successful()) {
                verifyArtifact(testCase, compiler, actual.artifact());
            } else if (actual.diagnosticCodes().isEmpty()) {
                throw new IllegalStateException("Failed compilation must provide stable diagnostic codes for "
                        + testCase.testCaseId());
            }

            return new CelConformanceReport.CaseResult(testCase.testCaseId(), true,
                    actual.successful() ? "COMPILE_SUCCESS" : "COMPILE_FAILURE",
                    actual.diagnosticCodes());
        }).toList();

        return new CelConformanceReport(suiteId, compiler.providerId(),
                compiler.providerVersion(), results);
    }

    private static void verifyArtifact(CelCompileTestCase testCase,
            CelCompilerProvider compiler, CompiledCelExpression artifact) {
        var request = testCase.request();
        requireEqual(compiler.providerId(), artifact.compilerProviderId(), "compiler provider id");
        requireEqual(compiler.providerVersion(), artifact.compilerProviderVersion(), "compiler provider version");
        requireEqual(request.environmentFingerprint(), artifact.environmentFingerprint(), "environment fingerprint");
        requireEqual(request.scopeFingerprint(), artifact.scopeFingerprint(), "scope fingerprint");
        requireEqual(request.functionRegistryFingerprint(), artifact.functionRegistryFingerprint(),
                "function registry fingerprint");
        requireEqual(request.semanticProfileFingerprint(), artifact.semanticProfileFingerprint(),
                "semantic profile fingerprint");
    }

    private static void requireEqual(String expected, String actual, String field) {
        if (!Objects.equals(expected, actual)) {
            throw new IllegalStateException("Compiled artifact " + field + " does not match compile request");
        }
    }

    private static void requireText(String value, String field) {
        if (value == null || value.isBlank()) {
            throw new IllegalArgumentException(field + " is required");
        }
    }
}
