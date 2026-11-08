package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.projectnessie.cel.common.types.Types.boolOf;

import java.util.List;
import java.util.Map;
import org.junit.jupiter.api.Test;
import org.projectnessie.cel.Env;
import org.projectnessie.cel.Library;
import org.projectnessie.cel.Program;
import org.projectnessie.cel.common.types.IntT;
import org.projectnessie.cel.common.types.StringT;
import org.projectnessie.cel.common.types.ref.TypeEnum;

class MultipleFunctionOverloadsTest {

    @Test
    void sameSourceFunctionNameSupportsTwoExactTypedOverloads() {
        FunctionDescriptor integer = descriptor(
                "wayang_is_even_int", List.of("int"));
        FunctionDescriptor string = descriptor(
                "wayang_is_even_string", List.of("string"));

        FunctionRegistry registry = FunctionRegistry.builder()
                .register(integer)
                .register(string)
                .build();

        CelFunctionBinding integerBinding = CelFunctionBinding.unary(integer, value -> {
            if (!(value instanceof IntT number)) {
                return org.projectnessie.cel.common.types.Err.newErr("expected int");
            }
            return boolOf(number.intValue() % 2L == 0L);
        });
        CelFunctionBinding stringBinding = CelFunctionBinding.unary(string, value -> {
            if (!(value instanceof StringT text)) {
                return org.projectnessie.cel.common.types.Err.newErr("expected string");
            }
            return boolOf(((String) text.value()).length() % 2 == 0);
        });

        Env env = Env.newCustomEnv(Library.Lib(
                new GovernedCelLibrary(registry, List.of(integerBinding, stringBinding))));

        assertBoolean(env, "wayang_is_even(4)", true);
        assertBoolean(env, "wayang_is_even(5)", false);
        assertBoolean(env, "wayang_is_even(\"abcd\")", true);
        assertBoolean(env, "wayang_is_even(\"abc\")", false);
    }

    @Test
    void ambiguousOrUnsupportedTypesFailDuringLibraryConstruction() {
        FunctionDescriptor unsupported = new FunctionDescriptor(
                "wayang.math", "wayang_is_even", "1.0.0", "wayang_is_even_custom",
                List.of("com.example.Custom"), "bool",
                FunctionDescriptor.Effect.PURE,
                FunctionDescriptor.Determinism.DETERMINISTIC, "1.0.0");
        FunctionRegistry registry = FunctionRegistry.builder().register(unsupported).build();
        CelFunctionBinding binding = CelFunctionBinding.unary(unsupported, value -> boolOf(true));

        org.junit.jupiter.api.Assertions.assertThrows(IllegalArgumentException.class,
                () -> new GovernedCelLibrary(registry, List.of(binding)));
    }

    private static FunctionDescriptor descriptor(String overloadId, List<String> argumentTypes) {
        return new FunctionDescriptor(
                "wayang.math", "wayang_is_even", "1.0.0", overloadId,
                argumentTypes, "bool", FunctionDescriptor.Effect.PURE,
                FunctionDescriptor.Determinism.DETERMINISTIC, "1.0.0");
    }

    private static void assertBoolean(Env env, String expression, boolean expected) {
        Env.AstIssuesTuple compiled = env.compile(expression);
        assertTrue(!compiled.hasIssues(), () -> String.valueOf(compiled.getIssues()));
        Program.EvalResult result = env.program(compiled.getAst()).eval(Map.of());
        assertEquals(TypeEnum.Bool, result.getVal().type().typeEnum());
        assertEquals(expected, result.getVal().value());
    }
}
