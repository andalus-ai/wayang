package tech.kayys.wayang.security.secrets.exception;

/**
 * Base exception for secret management operations.
 * Includes error codes for structured error handling.
 */
public class SecretException extends RuntimeException {
    private final ErrorCode errorCode;

    public SecretException(ErrorCode errorCode, String message) {
        super(message);
        this.errorCode = errorCode;
    }

    public SecretException(ErrorCode errorCode, String message, Throwable cause) {
        super(message, cause);
        this.errorCode = errorCode;
    }

    public ErrorCode getErrorCode() {
        return errorCode;
    }

    /**
     * Error codes for different failure scenarios
     */
    public enum ErrorCode {
        SECRET_NOT_FOUND("The requested secret does not exist"),
        PERMISSION_DENIED("Insufficient permissions to access this secret"),
        BACKEND_UNAVAILABLE("The secret backend is currently unavailable"),
        ENCRYPTION_FAILED("Failed to encrypt the secret"),
        DECRYPTION_FAILED("Failed to decrypt the secret"),
        INVALID_PATH("The secret path format is invalid"),
        QUOTA_EXCEEDED("Secret storage quota has been exceeded"),
        VERSION_NOT_FOUND("The requested secret version does not exist"),
        SECRET_EXPIRED("The requested secret has expired"),
        ROTATION_FAILED("Secret rotation operation failed"),
        INVALID_REQUEST("Invalid request parameters"),
        INTERNAL_ERROR("An internal error occurred");

        private final String description;

        ErrorCode(String description) {
            this.description = description;
        }

        public String getDescription() {
            return description;
        }
    }

    @Override
    public String toString() {
        return String.format("SecretException[%s]: %s", errorCode, getMessage());
    }
}
