package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.projectnessie.cel.common.types.Types.boolOf;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.projectnessie.cel.Ast;
import org.projectnessie.cel.Env;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.Library;
import org.projectnessie.cel.Program;
import org.projectnessie.cel.ProgramOption;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.common.types.Err;
import org.projectnessie.cel.common.types.IntT;
import org.projectnessie.cel.common.types.ref.Val;
import org.projectnessie.cel.interpreter.functions.Overload;

/**
 * Executable registration probes for one deliberately small, pure custom CEL function.
 *
 * <p>The fixture uses the public Library contract so declaration and runtime implementation are
 * installed together. It remains an isolated compatibility spike, not a production registry.
 */
class NessieCustomFunctionTest {

    private static final String FUNCTION = "wayang_is_even";
    private static final String OVERLOAD_ID = "wayang_is_even_int";

    @Test
    void functionIsNotAvailableWithoutAnExplicitLibrary() {
        Env env = Env.newCustomEnv();

        Env.AstIssuesTuple compiled = env.compile(FUNCTION + "(2)");

        assertTrue(compiled.hasIssues(),
                "A custom function must not become visible without explicit registration");
    }

    @Test
    void declaredAndImplementedFunctionEvaluatesForEvenAndOddValues() {
        Env env = Env.newCustomEnv(Library.Lib(new EvenFunctionLibrary()));
        assertBoolean(env, "wayang_is_even(2)", true);
        assertBoolean(env, "wayang_is_even(3)", false);
        assertBoolean(env, "wayang_is_even(0)", true);
        assertBoolean(env, "wayang_is_even(-7)", false);
    }

    @Test
    void functionIsDeterministicAcrossRepeatedEvaluations() {
        Env env = Env.newCustomEnv(Library.Lib(new EvenFunctionLibrary()));
        Ast ast = compile(env, "wayang_is_even(-9223372036854775808)");

        Program program = env.program(ast);
        for (int i = 0; i < 20; i++) {
            Program.EvalResult result = program.eval(Map.of());
            assertEquals(true, result.getVal().value(),
                    "The same immutable input must produce the same boolean result");
        }
    }

    private static void assertBoolean(Env env, String expression, boolean expected) {
        Ast ast = compile(env, expression);
        Program.EvalResult result = env.program(ast).eval(Map.of());
        assertEquals(expected, result.getVal().value());
    }

    private static Ast compile(Env env, String expression) {
        Env.AstIssuesTuple compiled = env.compile(expression);
        assertTrue(!compiled.hasIssues(),
                () -> "Expected expression to compile: " + expression + " / " + compiled.getIssues());
        return compiled.getAst();
    }

    private static final class EvenFunctionLibrary implements Library {

        @Override
        public List<EnvOption> getCompileOptions() {
            return List.of(EnvOption.declarations(
                    Decls.newFunction(
                            FUNCTION,
                            Decls.newOverload(OVERLOAD_ID, List.of(Decls.Int), Decls.Bool))));
        }

        @Override
        public List<ProgramOption> getProgramOptions() {
            return List.of(ProgramOption.functions(
                    Overload.function(FUNCTION, values -> {
                        if (values.length != 1 || !(values[0] instanceof IntT integer)) {
                            Val first = values.length == 0 ? null : values[0];
                            return Err.noSuchOverload(first, FUNCTION, first);
                        }
                        return boolOf(integer.intValue() % 2L == 0L);
                    })));
        }
    }
}
