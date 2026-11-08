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
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.common.types.Err;
import org.projectnessie.cel.common.types.IntT;
import org.projectnessie.cel.common.types.ref.TypeEnum;

/** Executable probes for explicit, governed custom CEL function registration. */
class NessieCustomFunctionTest {

    private static final String FUNCTION = "wayang_is_even";
    private static final String OVERLOAD_ID = "wayang_is_even_int";

    @Test
    void functionIsNotAvailableWithoutAnExplicitLibrary() {
        Env.AstIssuesTuple compiled = Env.newCustomEnv().compile(FUNCTION + "(2)");
        assertTrue(compiled.hasIssues(),
                "A custom function must not become visible without explicit registration");
    }

    @Test
    void declarationWithoutRuntimeImplementationEvaluatesToCelError() {
        Env env = Env.newCustomEnv(EnvOption.declarations(
                Decls.newFunction(FUNCTION,
                        Decls.newOverload(OVERLOAD_ID, List.of(Decls.Int), Decls.Bool))));
        Ast ast = compile(env, FUNCTION + "(2)");

        Program.EvalResult result = env.program(ast).eval(Map.of());

        assertEquals(TypeEnum.Err, result.getVal().type().typeEnum(),
                "A checker declaration must not be mistaken for an executable implementation");
    }

    @Test
    void declaredAndImplementedFunctionEvaluatesForEvenAndOddValues() {
        Env env = Env.newCustomEnv(Library.Lib(evenFunctionLibrary()));
        assertBoolean(env, FUNCTION + "(2)", true);
        assertBoolean(env, FUNCTION + "(3)", false);
        assertBoolean(env, FUNCTION + "(0)", true);
        assertBoolean(env, FUNCTION + "(-7)", false);
    }

    @Test
    void checkerRejectsWrongArgumentTypeBeforeEvaluation() {
        Env env = Env.newCustomEnv(Library.Lib(evenFunctionLibrary()));
        Env.AstIssuesTuple compiled = env.compile(FUNCTION + "(\"not-an-int\")");
        assertTrue(compiled.hasIssues(),
                "The declared int overload must not silently accept a string argument");
    }

    @Test
    void functionIsDeterministicAcrossRepeatedEvaluations() {
        Env env = Env.newCustomEnv(Library.Lib(evenFunctionLibrary()));
        Ast ast = compile(env, FUNCTION + "(-9223372036854775808)");
        Program program = env.program(ast);

        for (int i = 0; i < 20; i++) {
            Program.EvalResult result = program.eval(Map.of());
            assertEquals(TypeEnum.Bool, result.getVal().type().typeEnum());
            assertEquals(true, result.getVal().value());
        }
    }

    private static GovernedCelLibrary evenFunctionLibrary() {
        FunctionDescriptor descriptor = new FunctionDescriptor(
                "wayang.math", FUNCTION, "1.0.0", OVERLOAD_ID,
                List.of("int"), "bool",
                FunctionDescriptor.Effect.PURE,
                FunctionDescriptor.Determinism.DETERMINISTIC,
                "1.0.0");
        FunctionRegistry registry = FunctionRegistry.builder()
                .register(descriptor)
                .build()
                .requireExpressionSafe();

        CelFunctionBinding binding = CelFunctionBinding.unary(
                descriptor,
                EnvOption.declarations(Decls.newFunction(
                        FUNCTION,
                        Decls.newOverload(OVERLOAD_ID, List.of(Decls.Int), Decls.Bool))),
                value -> {
                    if (!(value instanceof IntT integer)) {
                        return Err.newErr("invalid argument for %s", FUNCTION);
                    }
                    return boolOf(integer.intValue() % 2L == 0L);
                });

        return new GovernedCelLibrary(registry, List.of(binding));
    }

    private static void assertBoolean(Env env, String expression, boolean expected) {
        Program.EvalResult result = env.program(compile(env, expression)).eval(Map.of());
        assertEquals(TypeEnum.Bool, result.getVal().type().typeEnum());
        assertEquals(expected, result.getVal().value());
    }

    private static Ast compile(Env env, String expression) {
        Env.AstIssuesTuple compiled = env.compile(expression);
        assertTrue(!compiled.hasIssues(),
                () -> "Expected expression to compile: " + expression + " / " + compiled.getIssues());
        return compiled.getAst();
    }
}
