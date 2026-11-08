package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.checker.Decls;
import org.projectnessie.cel.common.types.BoolT;

class GovernedCelLibraryTest {

    private static FunctionDescriptor descriptor(String overloadId) {
        return new FunctionDescriptor(
                "wayang.math", "wayang_is_even", "1.0.0", overloadId,
                List.of("int"), "bool",
                FunctionDescriptor.Effect.PURE,
                FunctionDescriptor.Determinism.DETERMINISTIC,
                "1.0.0");
    }

    private static CelFunctionBinding binding(FunctionDescriptor descriptor) {
        return CelFunctionBinding.unary(
                descriptor,
                EnvOption.declarations(Decls.newFunction(
                        descriptor.functionName(),
                        Decls.newOverload(descriptor.overloadId(), List.of(Decls.Int), Decls.Bool))),
                value -> BoolT.True);
    }

    @Test
    void libraryRequiresOneBindingForEveryRegistryEntry() {
        FunctionRegistry registry = FunctionRegistry.builder()
                .register(descriptor("wayang_is_even_int")).build();
        assertThrows(IllegalArgumentException.class,
                () -> new GovernedCelLibrary(registry, List.of()));
    }

    @Test
    void libraryRejectsBindingWhoseDescriptorDiffersFromRegistry() {
        FunctionDescriptor registered = descriptor("wayang_is_even_int");
        FunctionDescriptor differentVersion = new FunctionDescriptor(
                registered.functionId(), registered.functionName(), registered.functionVersion(),
                registered.overloadId(), registered.argumentTypes(), registered.resultType(),
                registered.effect(), registered.determinism(), "2.0.0");
        FunctionRegistry registry = FunctionRegistry.builder().register(registered).build();

        assertThrows(IllegalArgumentException.class,
                () -> new GovernedCelLibrary(registry, List.of(binding(differentVersion))));
    }

    @Test
    void unaryFactoryRejectsWrongDeclaredArity() {
        FunctionDescriptor binary = new FunctionDescriptor(
                "wayang.math", "wayang_add", "1.0.0", "wayang_add_int_int",
                List.of("int", "int"), "int", FunctionDescriptor.Effect.PURE,
                FunctionDescriptor.Determinism.DETERMINISTIC, "1.0.0");
        assertThrows(IllegalArgumentException.class,
                () -> CelFunctionBinding.unary(binary, EnvOption.declarations(), value -> BoolT.True));
    }

    @Test
    void libraryExposesOnlyOptionsFromGovernedTypedBindings() {
        FunctionDescriptor descriptor = descriptor("wayang_is_even_int");
        FunctionRegistry registry = FunctionRegistry.builder().register(descriptor).build();
        GovernedCelLibrary library = new GovernedCelLibrary(registry, List.of(binding(descriptor)));

        assertEquals(1, library.getCompileOptions().size());
        assertEquals(1, library.getProgramOptions().size());
        assertEquals(descriptor.overloadId(), binding(descriptor).runtimeOverloadId());
    }
}
