package tech.kayys.wayang.rest;

import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.enterprise.context.ApplicationScoped;

import org.eclipse.microprofile.config.inject.ConfigProperty;

import tech.kayys.wayang.api.TenantGate;

/**
 * Local kill switch: tenants listed in {@code wayang.tenants.suspended} (comma separated, restart to change) get 403 on every
 * request and their triggers stop firing. Numu's tenant lifecycle has no remote contract Wayang could call, so this is
 * configuration, not a sync with Numu's {@code TenantStatus}.
 */
@ApplicationScoped
public class TenantGateConfig {

    @ConfigProperty(name = "wayang.tenants.suspended")
    Optional<List<String>> suspended;

    private volatile TenantGate gate;

    public TenantGate gate() {
        if (gate == null) {
            gate = TenantGate.suspended(Set.copyOf(suspended.orElse(List.of())));
        }
        return gate;
    }
}
