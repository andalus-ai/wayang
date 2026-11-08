package tech.kayys.wayang.spike;

import java.util.Objects;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.ProgramOption;
import org.projectnessie.cel.interpreter.functions.Overload;
import org.projectnessie.cel.interpreter.functions.UnaryOp;
import org.projectnessie.cel.interpreter.functions.BinaryOp;

/**
 * Typed provider binding for one governed function overload.
 *
 * <p>Runtime ProgramOptions are constructed here from arity-specific operations.
 * Callers cannot inject an opaque ProgramOption whose dispatch behavior disagrees
 * with the descriptor metadata.
 */
public final class CelFunctionBinding {

    private final FunctionDescriptor descriptor;
    private final EnvOption declaration;
    private final ProgramOption implementation;

    private CelFunctionBinding(
            FunctionDescriptor descriptor,
            EnvOption declaration,
            ProgramOption implementation) {
        this.descriptor = Objects.requireNonNull(descriptor, "descriptor");
        this.declaration = Objects.requireNonNull(declaration, "declaration");
        this.implementation = Objects.requireNonNull(implementation, "implementation");
    }

    public static CelFunctionBinding unary(
            FunctionDescriptor descriptor,
            EnvOption declaration,
            UnaryOp implementation) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.argumentTypes().size() != 1) {
            throw new IllegalArgumentException("Unary binding requires exactly one declared argument");
        }
        return new CelFunctionBinding(
                descriptor,
                declaration,
                ProgramOption.functions(Overload.unary(
                        descriptor.overloadId(), Objects.requireNonNull(implementation, "implementation"))));
    }

    public static CelFunctionBinding binary(
            FunctionDescriptor descriptor,
            EnvOption declaration,
            BinaryOp implementation) {
        Objects.requireNonNull(descriptor, "descriptor");
        if (descriptor.argumentTypes().size() != 2) {
            throw new IllegalArgumentException("Binary binding requires exactly two declared arguments");
        }
        return new CelFunctionBinding(
                descriptor,
                declaration,
                ProgramOption.functions(Overload.binary(
                        descriptor.overloadId(), Objects.requireNonNull(implementation, "implementation"))));
    }

    public FunctionDescriptor descriptor() {
        return descriptor;
    }

    public String runtimeOverloadId() {
        return descriptor.overloadId();
    }

    public EnvOption declaration() {
        return declaration;
    }

    public ProgramOption implementation() {
        return implementation;
    }
}
