package tech.kayys.wayang.api;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

/**
 * The authenticated caller, established by the transport (JWT/OIDC claims etc.)
 * and passed explicitly to every service call. All data access is scoped to
 * {@link #tenantId()}.
 */
public record Principal(String tenantId, String userId, Set<Role> roles, Set<Permission> grants,
        Map<String, Set<Permission>> projectGrants) {

    private static final Pattern SAFE = Pattern.compile("[A-Za-z0-9_.@-]{1,128}");

    public Principal {
        if (tenantId == null || !SAFE.matcher(tenantId).matches()) {
            throw new IllegalArgumentException("Invalid tenant id");
        }
        if (userId == null || !SAFE.matcher(userId).matches()) {
            throw new IllegalArgumentException("Invalid user id");
        }
        roles = roles == null ? Set.of() : Set.copyOf(roles);
        grants = grants == null ? Set.of() : Set.copyOf(grants);
        Map<String, Set<Permission>> pg = new LinkedHashMap<>();
        if (projectGrants != null) {
            projectGrants.forEach((k, v) -> {
                if (v != null && !v.isEmpty()) {
                    pg.put(k, Set.copyOf(v));
                }
            });
        }
        projectGrants = Map.copyOf(pg);
    }

    public Principal(String tenantId, String userId, Set<Role> roles, Set<Permission> grants) {
        this(tenantId, userId, roles, grants, Map.of());
    }

    /** A principal whose access comes from coarse roles only (the JWT-claim mode). */
    public Principal(String tenantId, String userId, Set<Role> roles) {
        this(tenantId, userId, roles, Set.of());
    }

    /**
     * Tenant-wide permission. A grant held at tenant level covers every project; a grant held only on one project
     * does not count here, so anything that is not project-aware stays closed to project-scoped callers.
     */
    public boolean can(Permission p) {
        return grants.contains(p) || roles.stream().anyMatch(r -> r.permissions().contains(p));
    }

    /** The permission in the given project: tenant-wide grants apply everywhere, project grants only there. */
    public boolean can(Permission p, String projectId) {
        return can(p) || (projectId != null && projectGrants.getOrDefault(projectId, Set.of()).contains(p));
    }

    /** Held tenant-wide or on at least one project: the baseline for operations that then filter or check a project. */
    public boolean canAnywhere(Permission p) {
        return can(p) || projectGrants.values().stream().anyMatch(g -> g.contains(p));
    }
}
