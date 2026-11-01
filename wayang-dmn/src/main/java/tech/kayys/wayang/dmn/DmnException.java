package tech.kayys.wayang.dmn;

/** Raised for invalid decision models or for decisions that cannot be evaluated (e.g. UNIQUE hit policy violated). */
public class DmnException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public DmnException(String message) {
        super(message);
    }

    public DmnException(String message, Throwable cause) {
        super(message, cause);
    }
}
