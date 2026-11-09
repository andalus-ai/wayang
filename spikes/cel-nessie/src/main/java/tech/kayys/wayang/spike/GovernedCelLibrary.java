package tech.kayys.wayang.spike;

import com.google.api.expr.v1alpha1.Type;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.Library;
import org.projectnessie.cel.ProgramOption;
import org.projectnessie.cel.checker.Decls;

/**
 * Immutable CEL-Java adapter assembled from one exact, expression-safe registry.
 *
 * <p>Checker declarations are generated from the governed descriptors, grouping
 * overloads that share one CEL function name. Runtime dispatch options are
 * constructed only by typed CelFunctionBinding factories.
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
            if (!overloadId.equals(binding.runtimeOverloadId())) {
                throw new IllegalArgumentException(
                        "Runtime dispatcher key does not match registered overload ID: " + overloadId);
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

        Map<String, List<com.google.api.expr.v1alpha1.Decl.FunctionDecl.Overload>> declarations =
                new LinkedHashMap<>();
        List<ProgramOption> programs = new ArrayList<>();
        registry.descriptors().stream()
                .sorted(java.util.Comparator.comparing(FunctionDescriptor::functionName)
                        .thenComparing(FunctionDescriptor::overloadId))
                .forEach(descriptor -> {
                    List<Type> argumentTypes = descriptor.argumentTypes().stream()
                            .map(GovernedCelLibrary::celType)
                            .toList();
                    Type resultType = celType(descriptor.resultType());
                    declarations.computeIfAbsent(descriptor.functionName(), ignored -> new ArrayList<>())
                            .add(Decls.newOverload(descriptor.overloadId(), argumentTypes, resultType));
                    programs.add(byOverload.get(descriptor.overloadId()).implementation());
                });

        List<com.google.api.expr.v1alpha1.Decl> functionDeclarations = declarations.entrySet().stream()
                .map(entry -> Decls.newFunction(entry.getKey(),
                        entry.getValue().toArray(com.google.api.expr.v1alpha1.Decl.FunctionDecl.Overload[]::new)))
                .toList();

        this.compileOptions = List.of(EnvOption.declarations(
                functionDeclarations.toArray(com.google.api.expr.v1alpha1.Decl[]::new)));
        this.programOptions = List.copyOf(programs);
    }

    private static Type celType(String name) {
        return switch (name) {
            case "bool" -> Decls.Bool;
            case "bytes" -> Decls.Bytes;
            case "double" -> Decls.Double;
            case "int" -> Decls.Int;
            case "null" -> Decls.Null;
            case "string" -> Decls.String;
            case "uint" -> Decls.Uint;
            case "dyn" -> Decls.Dyn;
            default -> throw new IllegalArgumentException(
                    "Unsupported CEL primitive type in qualification adapter: " + name);
        };
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
