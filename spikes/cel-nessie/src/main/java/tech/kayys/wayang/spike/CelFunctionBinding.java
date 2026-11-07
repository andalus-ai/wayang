package tech.kayys.wayang.spike;

import java.util.Objects;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.ProgramOption;

/**
 * Provider-specific binding of one governed descriptor to its checker and runtime options.
 *
 * <p>CEL-Java's Dispatcher indexes runtime overloads by overload ID, not by the
 * source-level function name. The explicit runtimeOverloadId makes that boundary
 * auditable before opaque ProgramOptions are installed.
 */
public record CelFunctionBinding(
        FunctionDescriptor descriptor,
        String runtimeOverloadId,
        EnvOption declaration,
        ProgramOption implementation) {

    public CelFunctionBinding {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(runtimeOverloadId, "runtimeOverloadId");
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(implementation, "implementation");
        if (!descriptor.overloadId().equals(runtimeOverloadId)) {
            throw new IllegalArgumentException(
                    "Runtime overload ID does not match descriptor: " + runtimeOverloadId);
        }
    }
}
