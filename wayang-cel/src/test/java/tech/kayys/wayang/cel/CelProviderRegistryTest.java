package tech.kayys.wayang.cel;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

class CelProviderRegistryTest {
    private static final String ID = "test-cel";
    private static final String VERSION = "1.2.3";

    @Test
    void resolvesExactCompilerAndEvaluatorIdentity() {
        var compiler = new TestCompiler(ID, VERSION);
        var evaluator = new TestEvaluator(ID, VERSION);

        var registry = CelProviderRegistry.of(List.of(compiler), List.of(evaluator));

        assertSame(compiler, registry.requireCompiler(ID, VERSION));
        assertSame(evaluator, registry.requireEvaluator(ID, VERSION));
        assertEquals(List.of(new CelProviderRegistry.ProviderKey(ID, VERSION)), registry.providers());
    }

    @Test
    void rejectsDuplicateCompilerIdentityInsteadOfUsingClasspathOrder() {
        assertThrows(IllegalArgumentException.class, () -> CelProviderRegistry.of(
                List.of(new TestCompiler(ID, VERSION), new TestCompiler(ID, VERSION)),
                List.of(new TestEvaluator(ID, VERSION))));
    }

    @Test
    void rejectsDuplicateEvaluatorIdentity() {
        assertThrows(IllegalArgumentException.class, () -> CelProviderRegistry.of(
                List.of(new TestCompiler(ID, VERSION)),
                List.of(new TestEvaluator(ID, VERSION), new TestEvaluator(ID, VERSION))));
    }

    @Test
    void rejectsCompilerWithoutMatchingEvaluator() {
        var error = assertThrows(IllegalArgumentException.class, () -> CelProviderRegistry.of(
                List.of(new TestCompiler(ID, VERSION)),
                List.of(new TestEvaluator(ID, "9.9.9"))));
        assertTrue(error.getMessage().contains("identities must match"));
    }

    @Test
    void doesNotSilentlyFallBackToAnotherVersion() {
        var registry = CelProviderRegistry.of(
                List.of(new TestCompiler(ID, VERSION)),
                List.of(new TestEvaluator(ID, VERSION)));

        assertThrows(IllegalArgumentException.class, () -> registry.requireCompiler(ID, "1.2.4"));
        assertThrows(IllegalArgumentException.class, () -> registry.requireEvaluator(ID, "1.2.4"));
    }

    private record TestCompiler(String providerId, String providerVersion) implements CelCompilerProvider {
        @Override
        public CelCompileResult compile(CelCompileRequest request) {
            throw new UnsupportedOperationException("test double");
        }
    }

    private record TestEvaluator(String providerId, String providerVersion) implements CelEvaluatorProvider {
        @Override
        public CelEvaluationResult evaluate(CompiledCelExpression artifact,
                CelEvaluationContext context, CelEvaluationPolicy policy) {
            throw new UnsupportedOperationException("test double");
        }
    }
}
