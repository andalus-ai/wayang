package tech.kayys.wayang.expression;

/** Raised when an expression exceeds a resource limit (step budget). Never swallowed by lenient evaluation. */
public final class LimitExceededException extends ExpressionException {

    private static final long serialVersionUID = 1L;

    public LimitExceededException(String message, String source) {
        super(message, source, -1);
    }
}
