package tech.kayys.wayang.spike;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.util.List;
import org.junit.jupiter.api.Test;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.ProgramOption;

class GovernedCelLibraryTest {

    private static FunctionDescriptor descriptor(String overloadId) {
        return new FunctionDescriptor(
                "wayang.math", "wayang_is_even", "1.0.0", overloadId,
                List.of("int"), "bool",
                FunctionDescriptor.Effect.PURE,
                FunctionDescriptor.Determinism.DETERMINISTIC,
                "1.0.0");
    }

    @Test
    void libraryRequiresOneBindingForEveryRegistryEntry() {
        FunctionDescriptor descriptor = descriptor("wayang_is_even_int");
        FunctionRegistry registry = FunctionRegistry.builder().register(descriptor).build();

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
                () -> new GovernedCelLibrary(registry, List.of(
                        new CelFunctionBinding(differentVersion, EnvOption.declarations(), p -> p))));
    }

    @Test
    void libraryExposesExactlyTheGovernedCompileAndProgramOptions() {
        FunctionDescriptor descriptor = descriptor("wayang_is_even_int");
        FunctionRegistry registry = FunctionRegistry.builder().register(descriptor).build();
        EnvOption compileOption = EnvOption.declarations();
        ProgramOption programOption = p -> p;

        GovernedCelLibrary library = new GovernedCelLibrary(
                registry,
                List.of(new CelFunctionBinding(descriptor, compileOption, programOption)));

        assertEquals(List.of(compileOption), library.getCompileOptions());
        assertEquals(List.of(programOption), library.getProgramOptions());
    }
}
