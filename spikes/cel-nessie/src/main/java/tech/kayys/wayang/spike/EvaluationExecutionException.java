package tech.kayys.wayang.spike;

/** Stable host-side execution boundary error. */
public final class EvaluationExecutionException extends RuntimeException {
    private final String code;

    public EvaluationExecutionException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
