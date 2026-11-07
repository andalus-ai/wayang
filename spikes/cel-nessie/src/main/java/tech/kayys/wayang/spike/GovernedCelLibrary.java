package tech.kayys.wayang.spike;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.Library;
import org.projectnessie.cel.ProgramOption;

/**
 * Immutable CEL-Java adapter assembled from one exact, expression-safe registry.
 *
 * <p>Every registry descriptor must have exactly one provider binding, and every
 * binding must refer to the exact descriptor in that registry. This prevents
 * accidental partial registration and ungoverned runtime additions.
 */
public final class GovernedCelLibrary implements Library {

    private final List<EnvOption> compileOptions;
    private final List<ProgramOption> programOptions;

    public GovernedCelLibrary(
            FunctionRegistry registry,
            Collection<CelFunctionBinding> bindings) {
        Objects.requireNonNull(registry, "registry").requireExpressionSafe();
        Objects.requireNonNull(bindings, "bindings");

        Map<String, CelFunctionBinding> byOverload = new HashMap<>();
        for (CelFunctionBinding binding : bindings) {
            Objects.requireNonNull(binding, "binding");
            String overloadId = binding.descriptor().overloadId();
            FunctionDescriptor registered = registry.require(overloadId);
            if (!registered.equals(binding.descriptor())) {
                throw new IllegalArgumentException(
                        "Provider binding descriptor does not match registry entry: " + overloadId);
            }
            if (byOverload.putIfAbsent(overloadId, binding) != null) {
                throw new IllegalArgumentException("Duplicate provider binding: " + overloadId);
            }
        }

        if (byOverload.size() != registry.descriptors().size()) {
            List<String> missing = registry.descriptors().stream()
                    .map(FunctionDescriptor::overloadId)
                    .filter(id -> !byOverload.containsKey(id))
                    .sorted()
                    .toList();
            throw new IllegalArgumentException("Missing provider bindings for overloads: " + missing);
        }

        List<EnvOption> compile = new ArrayList<>();
        List<ProgramOption> programs = new ArrayList<>();
        registry.descriptors().stream()
                .map(descriptor -> byOverload.get(descriptor.overloadId()))
                .forEach(binding -> {
                    compile.add(binding.declaration());
                    programs.add(binding.implementation());
                });
        this.compileOptions = List.copyOf(compile);
        this.programOptions = List.copyOf(programs);
    }

    @Override
    public List<EnvOption> getCompileOptions() {
        return compileOptions;
    }

    @Override
    public List<ProgramOption> getProgramOptions() {
        return programOptions;
    }
}
