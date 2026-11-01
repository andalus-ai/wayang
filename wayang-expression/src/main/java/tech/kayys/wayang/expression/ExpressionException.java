package tech.kayys.wayang.expression;

/**
 * Raised for syntax errors, exceeded resource limits and (in strict mode)
 * evaluation errors. {@link #position()} is the 0-based character offset into
 * the source, or -1 when unknown.
 */
public class ExpressionException extends RuntimeException {

    private static final long serialVersionUID = 1L;

    private final int position;
    private final String source;

    public ExpressionException(String message, String source, int position) {
        super(message + (position >= 0 ? " (at position " + position + ")" : ""));
        this.position = position;
        this.source = source;
    }

    public int position() {
        return position;
    }

    public String source() {
        return source;
    }
}
