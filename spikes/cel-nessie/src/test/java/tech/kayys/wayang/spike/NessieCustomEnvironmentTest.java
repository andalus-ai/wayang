package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.Map;
import org.junit.jupiter.api.Test;
import org.projectnessie.cel.Ast;
import org.projectnessie.cel.Env;
import org.projectnessie.cel.Program;

/**
 * Baseline probes for the custom-environment boundary.
 *
 * <p>These tests deliberately use the lower-level API rather than ScriptHost,
 * whose default environment installs the standard CEL library.
 */
class NessieCustomEnvironmentTest {

    @Test
    void customEnvironmentCompilesAndEvaluatesBooleanLiteral() {
        Env env = Env.newCustomEnv();

        Env.AstIssuesTuple compiled = env.compile("true");

        assertTrue(!compiled.hasIssues(), "Boolean literal should compile in an empty environment");
        Ast ast = compiled.getAst();
        assertNotNull(ast, "Successful compilation must return an AST");

        Program program = env.program(ast);
        Program.EvalResult result = program.eval(Map.of());

        assertNotNull(result, "Evaluation result must not be null");
        assertNotNull(result.getVal(), "Evaluation value must not be null");
    }

    @Test
    void customEnvironmentDoesNotImplicitlyInstallStandardOperatorLibrary() {
        Env env = Env.newCustomEnv();

        Env.AstIssuesTuple compiled = env.compile("1 + 2");

        assertTrue(compiled.hasIssues(),
                "An empty custom environment must not silently inherit standard overload declarations");
    }

    @Test
    void undeclaredVariablesAreRejectedDuringStaticChecking() {
        Env env = Env.newCustomEnv();

        Env.AstIssuesTuple compiled = env.compile("customer.isPreferred");

        assertTrue(compiled.hasIssues(),
                "Undeclared names must fail during compile/check rather than being dynamically accepted");
    }
}
