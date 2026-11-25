package tech.kayys.wayang.agent;

/** Raised when a distributable Agent Package manifest is malformed or incompatible. */
public class AgentPackageException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AgentPackageException(String message) {
        super(message);
    }

    public AgentPackageException(String message, Throwable cause) {
        super(message, cause);
    }
}
