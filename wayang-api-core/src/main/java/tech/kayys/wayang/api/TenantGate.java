package tech.kayys.wayang.api;

import java.util.Set;

/**
 * Whether a tenant may use Wayang at all. Wayang does not own tenants; this is the seam for their lifecycle
 * (suspension) and a local kill switch. A suspended tenant gets 403 on every request and its triggers stop firing; runs
 * already in flight are not interrupted.
 */
@FunctionalInterface
public interface TenantGate {

    boolean active(String tenant);

    TenantGate ALLOW_ALL = t -> true;

    static TenantGate suspended(Set<String> suspended) {
        Set<String> s = Set.copyOf(suspended);
        return t -> !s.contains(t);
    }

    default void require(String tenant) {
        if (!active(tenant)) {
            throw new ApiException(ApiException.Code.FORBIDDEN, "Tenant is suspended");
        }
    }
}
