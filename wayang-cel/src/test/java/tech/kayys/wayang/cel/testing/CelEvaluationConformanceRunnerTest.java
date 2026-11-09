package tech.kayys.wayang.cel.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.cel.*;

class CelEvaluationConformanceRunnerTest {
    private static final String PROVIDER = "fixture-engine";
    private static final String VERSION = "1.0";
    private static final String ARTIFACT = "sha256:artifact";
    private static final String SCOPE = "sha256:scope";
    private static final String ENV = "sha256:env";

    @Test
    void reportsBooleanFalseAsSuccessfulValue() {
        var artifact = artifact();
        var testCase = testCase(new CelTestExpectation(CelTestExpectation.Kind.VALUE,
                new CelValue.BoolValue(false), null));
        var evaluator = new FixedEvaluator(new CelEvaluationResult(CelEvaluationStatus.SUCCESS,
                new CelValue.BoolValue(false), List.of(), 10));

        var report = runner().run("boolean-contracts", evaluator, artifact,
                List.of(testCase), policy());

        assertTrue(report.successful());
        assertEquals(1, report.passedCount());
        assertEquals(0, report.failedCount());
    }

    @Test
    void requiresExactFailureStatusInsteadOfTreatingItAsFalse() {
        var artifact = artifact();
        var expected = new CelTestExpectation(CelTestExpectation.Kind.FAILURE, null,
                CelEvaluationStatus.MISSING_VARIABLE);
        var evaluator = new FixedEvaluator(CelEvaluationResult.failure(
                CelEvaluationStatus.TYPE_ERROR, List.of("TYPE_MISMATCH"), 10));

        var report = runner().run("failure-contracts", evaluator, artifact,
                List.of(testCase(expected)), policy());

        assertFalse(report.successful());
        assertEquals(1, report.failedCount());
    }

    @Test
    void rejectsFixtureForDifferentScope() {
        var fixture = new CelFixture("fixture-1", "different-scope", Map.of());
        var testCase = new CelTestCase("case-1", ARTIFACT, fixture,
                new CelTestExpectation(CelTestExpectation.Kind.VALUE,
                        new CelValue.BoolValue(true), null));

        assertThrows(IllegalArgumentException.class, () -> runner().run("scope", new FixedEvaluator(
                CelEvaluationResult.success(new CelValue.BoolValue(true), 1)), artifact(),
                List.of(testCase), policy()));
    }

    @Test
    void rejectsEvaluatorDifferentFromArtifactProviderPin() {
        var evaluator = new FixedEvaluator(CelEvaluationResult.success(new CelValue.BoolValue(true), 1),
                "other-engine", VERSION);
        assertThrows(IllegalArgumentException.class, () -> runner().run("provider-pin", evaluator,
                artifact(), List.of(testCase(new CelTestExpectation(CelTestExpectation.Kind.VALUE,
                        new CelValue.BoolValue(true), null))), policy()));
    }

    private static CelEvaluationConformanceRunner runner() {
        return new CelEvaluationConformanceRunner(new DefaultCelTestComparator());
    }

    private static CelEvaluationPolicy policy() {
        return new CelEvaluationPolicy(100, 100, 4096, 1000, "policy-v1");
    }

    private static CompiledCelExpression artifact() {
        return new CompiledCelExpression("artifact-1", ARTIFACT, "1", PROVIDER, VERSION,
                ENV, SCOPE, "functions-v1", "semantics-v1");
    }

    private static CelTestCase testCase(CelTestExpectation expectation) {
        return new CelTestCase("case-1", ARTIFACT,
                new CelFixture("fixture-1", SCOPE, Map.of()), expectation);
    }

    private static final class FixedEvaluator implements CelEvaluatorProvider {
        private final CelEvaluationResult result;
        private final String id;
        private final String version;

        private FixedEvaluator(CelEvaluationResult result) {
            this(result, PROVIDER, VERSION);
        }

        private FixedEvaluator(CelEvaluationResult result, String id, String version) {
            this.result = result;
            this.id = id;
            this.version = version;
        }

        @Override public String providerId() { return id; }
        @Override public String providerVersion() { return version; }
        @Override public CelEvaluationResult evaluate(CompiledCelExpression artifact,
                CelEvaluationContext context, CelEvaluationPolicy policy) { return result; }
    }
}
