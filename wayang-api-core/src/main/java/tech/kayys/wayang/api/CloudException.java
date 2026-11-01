package tech.kayys.wayang.api;

/** A failure talking to a cloud secret service. The message is safe to log: no credentials, no response bodies. */
public final class CloudException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    private final int status;

    public CloudException(String message, int status) {
        super(message);
        this.status = status;
    }

    /** HTTP status, or 0 when there was none. */
    public int status() {
        return status;
    }
}
