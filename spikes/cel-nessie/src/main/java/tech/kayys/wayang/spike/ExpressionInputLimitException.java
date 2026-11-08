package tech.kayys.wayang.spike;

/** Rejected expression or activation input, identified by a stable non-sensitive code. */
public final class ExpressionInputLimitException extends IllegalArgumentException {

    private final String code;

    public ExpressionInputLimitException(String code) {
        super("Expression input rejected: " + code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
