package tech.kayys.wayang.api;

import java.util.List;

/** Error with a stable machine-readable code that every transport maps to its own status. */
public class ApiException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public enum Code {
        BAD_REQUEST(400), UNAUTHORIZED(401), FORBIDDEN(403), NOT_FOUND(404), CONFLICT(409), VALIDATION_FAILED(422),
        TOO_MANY_REQUESTS(429), UNAVAILABLE(503), INTERNAL(500);

        private final int http;

        Code(int http) {
            this.http = http;
        }

        public int httpStatus() {
            return http;
        }
    }

    private final Code code;
    private final transient List<String> details;

    public ApiException(Code code, String message) {
        this(code, message, List.of());
    }

    public ApiException(Code code, String message, List<String> details) {
        super(message);
        this.code = code;
        this.details = List.copyOf(details);
    }

    public Code code() {
        return code;
    }

    public List<String> details() {
        return details;
    }

    public static ApiException forbidden(Permission p) {
        return new ApiException(Code.FORBIDDEN, "Missing permission " + p);
    }

    public static ApiException notFound(String what) {
        return new ApiException(Code.NOT_FOUND, what + " not found");
    }

    public static ApiException badRequest(String message) {
        return new ApiException(Code.BAD_REQUEST, message);
    }
}
