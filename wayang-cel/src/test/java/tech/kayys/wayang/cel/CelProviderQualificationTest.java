package tech.kayys.wayang.cel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;

class CelProviderQualificationTest {
    private static final String ID = "qualification-fixture";
    private static final String VERSION = "1.0";

    @Test
    void registryRejectsProviderWithoutVerifiedCapabilities() {
        var registry = CelProviderRegistry.of(
                List.of(new FixtureCompiler(CelProviderCapabilities.unqualified())),
                List.of(new FixtureEvaluator(CelProviderCapabilities.unqualified())));

        var compilerFailure = assertThrows(IllegalStateException.class,
                () -> registry.requireQualifiedCompiler(ID, VERSION));
        assertTrue(compilerFailure.getMessage().contains("STATIC_TYPE_CHECKING_UNVERIFIED"));

        var evaluatorFailure = assertThrows(IllegalStateException.class,
                () -> registry.requireQualifiedEvaluator(ID, VERSION));
        assertTrue(evaluatorFailure.getMessage().contains("HARD_EVALUATION_COST_LIMIT_UNVERIFIED"));
    }

    @Test
    void registryAllowsOnlyProvidersDeclaringAllRequiredGates() {
        var complete = new CelProviderCapabilities(true, true, true, true,
                true, true, true, true);
        var compiler = new FixtureCompiler(complete);
        var evaluator = new FixtureEvaluator(complete);
        var registry = CelProviderRegistry.of(List.of(compiler), List.of(evaluator));

        assertSame(compiler, registry.requireQualifiedCompiler(ID, VERSION));
        assertSame(evaluator, registry.requireQualifiedEvaluator(ID, VERSION));
    }

    private record FixtureCompiler(CelProviderCapabilities capabilities)
            implements CelCompilerProvider {
        @Override public String providerId() { return ID; }
        @Override public String providerVersion() { return VERSION; }
        @Override public CelCompileResult compile(CelCompileRequest request) {
            throw new UnsupportedOperationException("qualification fixture");
        }
    }

    private record FixtureEvaluator(CelProviderCapabilities capabilities)
            implements CelEvaluatorProvider {
        @Override public String providerId() { return ID; }
        @Override public String providerVersion() { return VERSION; }
        @Override public CelEvaluationResult evaluate(CompiledCelExpression artifact,
                CelEvaluationContext context, CelEvaluationPolicy policy) {
            throw new UnsupportedOperationException("qualification fixture");
        }
    }
}
