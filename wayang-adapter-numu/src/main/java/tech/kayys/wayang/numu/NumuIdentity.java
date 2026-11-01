package tech.kayys.wayang.numu;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

/**
 * What Wayang needs from Numu identity, independent of transport. Mirrors Numu's {@code AuthCheckService}
 * ({@code ResolvePrincipal}, {@code CheckPermissions}); the gRPC binding implements it, tests use an in-memory
 * fake that follows Numu's scope-inheritance rule.
 *
 * <p>Implementations must <b>fail closed</b>: an unreachable or erroring identity service is reported by throwing
 * {@link IdentityUnavailableException}, never by returning "allowed".
 */
public interface NumuIdentity {

    enum PrincipalType { USER, SERVICE_ACCOUNT }

    /** A validated credential's principal. */
    record Resolved(PrincipalType type, UUID id, String displayName) {
    }

    /** One (scope path, permission code) question. */
    record Check(String path, String permission) {
    }

    /** Validates a bearer JWT or personal access token (`pat_...`). Empty = not valid (unknown, expired, revoked). */
    Optional<Resolved> resolve(String credential);

    /** Answers each check, in order. Same length as {@code checks}. */
    List<Boolean> check(Resolved principal, List<Check> checks);

    /** The identity service could not answer; the caller must deny and may report 503. */
    class IdentityUnavailableException extends RuntimeException {
        private static final long serialVersionUID = 1L;

        public IdentityUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
