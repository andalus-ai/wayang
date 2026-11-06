package tech.kayys.wayang.cel.testing;

import java.util.List;
import java.util.Objects;

import tech.kayys.wayang.cel.CelEvaluationContext;
import tech.kayys.wayang.cel.CelEvaluationPolicy;
import tech.kayys.wayang.cel.CelEvaluationResult;
import tech.kayys.wayang.cel.CelEvaluatorProvider;
import tech.kayys.wayang.cel.CompiledCelExpression;

/**
 * Runs deterministic fixtures against one exact evaluator and compiled artifact.
 *
 * <p>This runner deliberately does not catch provider exceptions: an adapter
 * throwing outside the result contract is a provider defect and must fail the
 * conformance job, not be disguised as an ordinary expression error.</p>
 */
public final class CelEvaluationConformanceRunner {
    private final CelTestComparator comparator;

    public CelEvaluationConformanceRunner(CelTestComparator comparator) {
        this.comparator = Objects.requireNonNull(comparator, "comparator");
    }

    public CelConformanceReport run(String suiteId, CelEvaluatorProvider evaluator,
            CompiledCelExpression artifact, List<CelTestCase> testCases,
            CelEvaluationPolicy policy) {
        Objects.requireNonNull(evaluator, "evaluator");
        Objects.requireNonNull(artifact, "artifact");
        Objects.requireNonNull(testCases, "testCases");
        Objects.requireNonNull(policy, "policy");

        if (!artifact.compilerProviderId().equals(evaluator.providerId())
                || !artifact.compilerProviderVersion().equals(evaluator.providerVersion())) {
            throw new IllegalArgumentException("Evaluator identity does not match the artifact's pinned provider");
        }

        List<CelConformanceReport.CaseResult> results = testCases.stream().map(testCase -> {
            if (!artifact.artifactFingerprint().equals(testCase.artifactFingerprint())) {
                throw new IllegalArgumentException("Test case " + testCase.testCaseId()
                        + " references a different compiled artifact");
            }
            if (!artifact.scopeFingerprint().equals(testCase.fixture().scopeFingerprint())) {
                throw new IllegalArgumentException("Test case " + testCase.testCaseId()
                        + " fixture scope does not match the compiled artifact");
            }
            CelEvaluationContext context = new CelEvaluationContext(
                    testCase.fixture().fixtureId(), artifact.environmentFingerprint(),
                    testCase.fixture().scopeFingerprint(), testCase.fixture().variables());
            CelEvaluationResult actual = Objects.requireNonNull(
                    evaluator.evaluate(artifact, context, policy), "provider returned null result");
            boolean passed = comparator.matches(testCase.expectation(), actual);
            return new CelConformanceReport.CaseResult(testCase.testCaseId(), passed,
                    actual.status().name(), actual.diagnosticCodes());
        }).toList();

        return new CelConformanceReport(suiteId, evaluator.providerId(),
                evaluator.providerVersion(), results);
    }
}
