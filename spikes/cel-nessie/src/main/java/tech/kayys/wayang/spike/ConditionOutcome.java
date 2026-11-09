package tech.kayys.wayang.spike;

import java.util.Objects;
import org.projectnessie.cel.common.types.ref.TypeEnum;
import org.projectnessie.cel.common.types.ref.Val;

/**
 * Strict, provider-adapter condition result. Only CEL booleans become decisions.
 *
 * <p>False, CEL errors, unknowns, nulls, non-booleans, and host failures remain
 * distinct so callers cannot accidentally collapse evaluation failure to false.
 */
public record ConditionOutcome(Kind kind, Boolean booleanValue, String diagnosticCode) {

    public ConditionOutcome {
        Objects.requireNonNull(kind, "kind");
        if ((kind == Kind.TRUE || kind == Kind.FALSE) != (booleanValue != null)) {
            throw new IllegalArgumentException("Only TRUE/FALSE outcomes carry a boolean value");
        }
        if (diagnosticCode != null && diagnosticCode.isBlank()) {
            throw new IllegalArgumentException("diagnosticCode must be null or non-blank");
        }
    }

    public static ConditionOutcome fromCelValue(Val value) {
        if (value == null) {
            return new ConditionOutcome(Kind.NULL, null, "CEL_NULL_RESULT");
        }
        TypeEnum type = value.type().typeEnum();
        return switch (type) {
            case Bool -> value.booleanValue()
                    ? new ConditionOutcome(Kind.TRUE, true, null)
                    : new ConditionOutcome(Kind.FALSE, false, null);
            case Err -> new ConditionOutcome(Kind.ERROR, null, "CEL_EVALUATION_ERROR");
            case Unknown -> new ConditionOutcome(Kind.UNKNOWN, null, "CEL_UNKNOWN_RESULT");
            case Null -> new ConditionOutcome(Kind.NULL, null, "CEL_NULL_RESULT");
            default -> new ConditionOutcome(Kind.NON_BOOLEAN, null, "CEL_NON_BOOLEAN_RESULT");
        };
    }

    /**
     * Maps a host-side failure without exposing exception messages or implementation details.
     */
    public static ConditionOutcome hostFailure(Throwable failure) {
        Objects.requireNonNull(failure, "failure");
        return new ConditionOutcome(Kind.HOST_FAILURE, null, "CEL_HOST_EVALUATION_FAILURE");
    }

    public enum Kind {
        TRUE,
        FALSE,
        ERROR,
        UNKNOWN,
        NULL,
        NON_BOOLEAN,
        HOST_FAILURE
    }
}
