package tech.kayys.wayang.rest;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import io.quarkus.grpc.GrpcClient;
import tech.kayys.numou.identity.grpc.AuthCheckService;
import tech.kayys.numou.identity.grpc.CheckPermissionsRequest;
import tech.kayys.numou.identity.grpc.PathPermissionPair;
import tech.kayys.numou.identity.grpc.PrincipalType;
import tech.kayys.numou.identity.grpc.ResolvePrincipalRequest;
import tech.kayys.wayang.numu.NumuIdentity;

/**
 * {@link NumuIdentity} over Numu's {@code AuthCheckService} gRPC contract (Mutiny client generated from
 * {@code src/main/proto/auth_check.proto}).
 *
 * <p><b>Never compiled or run</b>: it needs the Quarkus gRPC code generator, which was not available. It is a thin
 * mapping; the behaviour that matters (tenant scoping, fail-closed, caching) lives in the tested
 * {@code NumuPrincipalResolver}. Any gRPC failure is reported as unavailable, never as "allowed".
 */
@ApplicationScoped
public class NumuGrpcIdentity implements NumuIdentity {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    @Inject
    @GrpcClient("numu")
    AuthCheckService client;

    @Override
    public Optional<Resolved> resolve(String credential) {
        try {
            var r = client.resolvePrincipal(ResolvePrincipalRequest.newBuilder().setCredential(credential).build())
                    .await().atMost(TIMEOUT);
            if (!r.getValid()) {
                return Optional.empty();
            }
            PrincipalType type = r.getPrincipalType();
            if (type != PrincipalType.USER && type != PrincipalType.SERVICE_ACCOUNT) {
                return Optional.empty(); // unspecified or unknown: do not guess
            }
            return Optional.of(new Resolved(
                    type == PrincipalType.USER ? NumuIdentity.PrincipalType.USER : NumuIdentity.PrincipalType.SERVICE_ACCOUNT,
                    UUID.fromString(r.getPrincipalId()), r.getDisplayName()));
        } catch (RuntimeException e) {
            throw new IdentityUnavailableException("Numu ResolvePrincipal failed", e);
        }
    }

    @Override
    public List<Boolean> check(Resolved principal, List<Check> checks) {
        try {
            var req = CheckPermissionsRequest.newBuilder()
                    .setPrincipalType(principal.type() == NumuIdentity.PrincipalType.USER ? PrincipalType.USER : PrincipalType.SERVICE_ACCOUNT)
                    .setPrincipalId(principal.id().toString());
            for (Check c : checks) {
                req.addChecks(PathPermissionPair.newBuilder().setRequestedPath(c.path()).setRequiredPermission(c.permission()));
            }
            var res = client.checkPermissions(req.build()).await().atMost(TIMEOUT);
            return new ArrayList<>(res.getResultsList());
        } catch (RuntimeException e) {
            throw new IdentityUnavailableException("Numu CheckPermissions failed", e);
        }
    }
}
