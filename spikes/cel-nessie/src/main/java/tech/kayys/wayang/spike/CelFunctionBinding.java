package tech.kayys.wayang.spike;

import java.util.Objects;
import org.projectnessie.cel.EnvOption;
import org.projectnessie.cel.ProgramOption;

/**
 * Provider-specific binding of one governed descriptor to its checker and runtime options.
 *
 * <p>The descriptor is the stable Wayang identity; EnvOption and ProgramOption
 * are deliberately confined to this CEL-Java adapter boundary.
 */
public record CelFunctionBinding(
        FunctionDescriptor descriptor,
        EnvOption declaration,
        ProgramOption implementation) {

    public CelFunctionBinding {
        Objects.requireNonNull(descriptor, "descriptor");
        Objects.requireNonNull(declaration, "declaration");
        Objects.requireNonNull(implementation, "implementation");
    }
}
