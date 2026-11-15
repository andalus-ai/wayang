package tech.kayys.wayang.management.client;

/** Non-2xx response from the Wayang Console runtime management API. */
public final class ManagementApiException extends RuntimeException {
    private final int statusCode;
    private final String errorCode;

    public ManagementApiException(int statusCode, String errorCode, String message) {
        super(message == null || message.isBlank() ? "Management API request failed" : message);
        this.statusCode = statusCode;
        this.errorCode = errorCode == null || errorCode.isBlank() ? "http_error" : errorCode;
    }

    public int statusCode() {
        return statusCode;
    }

    public String errorCode() {
        return errorCode;
    }

    /** Whether a caller may retry after applying bounded backoff. */
    public boolean retryable() {
        return statusCode == 408 || statusCode == 425 || statusCode == 429 || statusCode >= 500;
    }

    /** Credential errors require operator action; retrying the same token cannot repair them. */
    public boolean credentialRejected() {
        return statusCode == 401 || statusCode == 403;
    }
}
