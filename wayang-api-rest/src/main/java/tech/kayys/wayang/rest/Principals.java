package tech.kayys.wayang.rest;

import java.util.EnumSet;
import java.util.Set;

import jakarta.enterprise.context.RequestScoped;
import jakarta.enterprise.inject.Instance;
import jakarta.inject.Inject;
import jakarta.ws.rs.core.HttpHeaders;

import org.eclipse.microprofile.config.inject.ConfigProperty;
import org.eclipse.microprofile.jwt.JsonWebToken;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.Role;
import tech.kayys.wayang.numu.NumuPrincipalResolver;

/**
 * Establishes the {@link Principal} of the current request. Two modes ({@code wayang.auth.mode}):
 *
 * <ul>
 * <li>{@code jwt} (default): tenant from the configurable claim (default {@code tenant_id}), user from {@code sub},
 * roles from the token groups; needs Quarkus OIDC.</li>
 * <li>{@code numu}: the bearer credential (JWT or personal access token) is validated by Numu identity and the
 * permissions are Numu's, resolved for the tenant named in the {@code X-Tenant} header (never trusted without a
 * membership check). Set {@code quarkus.oidc.enabled=false} in this mode, or OIDC rejects PATs first.</li>
 * </ul>
 */
@RequestScoped
public class Principals {

    @Inject
    Instance<JsonWebToken> jwt;

    @Inject
    Instance<HttpHeaders> headers;

    @Inject
    Instance<NumuPrincipalResolver> numu;

    @ConfigProperty(name = "wayang.auth.mode", defaultValue = "jwt")
    String mode;

    @ConfigProperty(name = "wayang.auth.tenant-claim", defaultValue = "tenant_id")
    String tenantClaim;

    @Inject
    TenantGateConfig tenantGate;

    private Principal cached;

    public Principal current() {
        if (cached == null) {
            Principal p = "numu".equalsIgnoreCase(mode) ? fromNumu() : fromJwt();
            tenantGate.gate().require(p.tenantId()); // suspended tenants are refused before anything else runs
            cached = p;
        }
        return cached;
    }

    /** The principal if this request already resolved one; never calls the identity service (used by the audit filter). */
    public Principal peek() {
        return cached;
    }

    private Principal fromNumu() {
        String grpcAuth = GrpcAuthInterceptor.AUTHORIZATION.get(); // non-null only inside a gRPC call
        if (grpcAuth != null) {
            return fromNumu(grpcAuth, GrpcAuthInterceptor.TENANT.get());
        }
        if (!headers.isResolvable()) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        return fromNumu(headers.get().getHeaderString("Authorization"), headers.get().getHeaderString("X-Tenant"));
    }

    /** Numu authentication from a raw {@code Authorization} value and tenant (HTTP headers or gRPC metadata). */
    Principal fromNumu(String authorization, String tenant) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        return numu.get().resolve(authorization.substring(7).trim(), tenant);
    }

    private Principal fromJwt() {
        JsonWebToken token = jwt.isResolvable() ? jwt.get() : null;
        if (token == null || token.getName() == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        Object tenant = token.getClaim(tenantClaim);
        if (tenant == null) {
            throw new ApiException(ApiException.Code.FORBIDDEN, "Token has no '" + tenantClaim + "' claim");
        }
        Set<Role> roles = EnumSet.noneOf(Role.class);
        for (String g : token.getGroups()) {
            try {
                roles.add(Role.valueOf(g.toUpperCase(java.util.Locale.ROOT)));
            } catch (IllegalArgumentException ignored) {
                // not a Wayang role
            }
        }
        return new Principal(String.valueOf(tenant), token.getSubject() == null ? token.getName() : token.getSubject(), roles);
    }
}
