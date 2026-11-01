package tech.kayys.wayang.runtime;

import java.util.Map;

/** Posts an OAuth2 form to a token endpoint and returns the parsed JSON object. Implemented over the guarded HTTP client. */
@FunctionalInterface
public interface OAuthTokenEndpoint {

    /** Thrown for an RFC 6749 error response ({@code error} field), e.g. {@code invalid_grant}. */
    final class OAuthError extends Exception {
        private static final long serialVersionUID = 1L;
        private final String error;

        public OAuthError(String error) {
            super("OAuth error: " + error);
            this.error = error;
        }

        public String error() {
            return error;
        }
    }

    /** @throws OAuthError when the server answered with an OAuth error; other exceptions are transport failures */
    Map<String, Object> post(String url, Map<String, String> form) throws Exception;
}
