package tech.kayys.wayang.cel.testing;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.junit.jupiter.api.Test;
import tech.kayys.wayang.cel.*;

class CelCompilationConformanceRunnerTest {
    private static final String PROVIDER = "fixture-engine";
    private static final String VERSION = "1.0";
    private static final String ENV = "sha256:env";
    private static final String SCOPE = "sha256:scope";
    private static final String FUNCTIONS = "sha256:functions";
    private static final String SEMANTICS = "sha256:semantics";

    @Test
    void acceptsSuccessfulCompilationOnlyWhenArtifactProvenanceMatches() {
        var compiler = new FixedCompiler(true, artifact());
        var report = new CelCompilationConformanceRunner().run("compile-success", compiler,
                List.of(new CelCompileTestCase("valid", request(), true)));

        assertTrue(report.successful());
        assertEquals("COMPILE_SUCCESS", report.cases().getFirst().actualStatus());
    }

    @Test
    void acceptsExpectedCompileFailureWithStableDiagnostic() {
        var compiler = new FixedCompiler(false, null);
        var report = new CelCompilationConformanceRunner().run("compile-failure", compiler,
                List.of(new CelCompileTestCase("invalid", request(), false)));

        assertTrue(report.successful());
        assertEquals(List.of("PARSE_ERROR"), report.cases().getFirst().diagnosticCodes());
    }

    @Test
    void rejectsArtifactWithDifferentScopeFingerprint() {
        var wrongArtifact = new CompiledCelExpression("artifact", "sha256:artifact", "1",
                PROVIDER, VERSION, ENV, "wrong-scope", FUNCTIONS, SEMANTICS);
        assertThrows(IllegalStateException.class, () -> new CelCompilationConformanceRunner().run(
                "scope-mismatch", new FixedCompiler(true, wrongArtifact),
                List.of(new CelCompileTestCase("valid", request(), true))));
    }

    @Test
    void rejectsUnexpectedCompileOutcome() {
        assertThrows(IllegalStateException.class, () -> new CelCompilationConformanceRunner().run(
                "unexpected", new FixedCompiler(false, null),
                List.of(new CelCompileTestCase("expected-valid", request(), true))));
    }

    private static CelCompileRequest request() {
        return new CelCompileRequest("expression-1", "customer.age >= 18", "bool",
                ENV, SCOPE, FUNCTIONS, SEMANTICS, "sha256:compile-policy");
    }

    private static CompiledCelExpression artifact() {
        return new CompiledCelExpression("expression-1", "sha256:artifact", "1",
                PROVIDER, VERSION, ENV, SCOPE, FUNCTIONS, SEMANTICS);
    }

    private static final class FixedCompiler implements CelCompilerProvider {
        private final boolean successful;
        private final CompiledCelExpression artifact;

        private FixedCompiler(boolean successful, CompiledCelExpression artifact) {
            this.successful = successful;
            this.artifact = artifact;
        }

        @Override public String providerId() { return PROVIDER; }
        @Override public String providerVersion() { return VERSION; }

        @Override
        public CelCompileResult compile(CelCompileRequest request) {
            return successful
                    ? new CelCompileResult(true, artifact, List.of())
                    : new CelCompileResult(false, null, List.of("PARSE_ERROR"));
        }
    }
}
