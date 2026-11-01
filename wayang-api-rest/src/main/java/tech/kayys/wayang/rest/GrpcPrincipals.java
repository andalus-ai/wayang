package tech.kayys.wayang.rest;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.inject.Inject;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.numu.NumuPrincipalResolver;

/**
 * Numu authentication for gRPC streams, which must leave the call thread (the identity lookup is a remote call) and so cannot use
 * the request-scoped {@link Principals}. Application scoped: needs no request context. Not used in jwt mode.
 */
@ApplicationScoped
public class GrpcPrincipals {

    @ConfigProperty(name = "wayang.auth.mode", defaultValue = "jwt")
    String mode;

    @Inject
    jakarta.enterprise.inject.Instance<NumuPrincipalResolver> numu;
    @Inject
    TenantGateConfig tenantGate;

    public boolean numuMode() {
        return "numu".equalsIgnoreCase(mode);
    }

    /** @param authorization the raw {@code Authorization} metadata value ({@code Bearer ...}) */
    public Principal resolve(String authorization, String tenant) {
        if (authorization == null || !authorization.regionMatches(true, 0, "Bearer ", 0, 7)) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        Principal p = numu.get().resolve(authorization.substring(7).trim(), tenant);
        tenantGate.gate().require(p.tenantId());
        return p;
    }
}
