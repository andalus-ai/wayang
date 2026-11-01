package tech.kayys.wayang.numu;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;

/**
 * Turns a bearer credential plus a requested tenant into a Wayang {@link Principal} using Numu.
 *
 * <ul>
 *   <li>The tenant is only ever <em>requested</em> by the client; it is granted only if the principal holds at
 *       least one Wayang permission under that tenant's scope. "No such tenant" and "not a member" are the same
 *       answer, so tenants cannot be enumerated.</li>
 *   <li>{@code wayang.admin} implies every permission.</li>
 *   <li>Fails closed: identity errors become 503, never access.</li>
 *   <li>Results are cached briefly (default 30 s) so a request does not cost two remote calls. A revoked role or
 *       token therefore keeps working for up to the TTL; set it to zero to disable caching.</li>
 * </ul>
 */
public final class NumuPrincipalResolver {

    private static final int MAX_CACHE = 10_000;

    private final NumuIdentity identity;
    private final NumuScheme scheme;
    private final Clock clock;
    private final Duration ttl;

    /** Per-tenant project ids, to ask Numu about project-scoped grants; empty = tenant-wide grants only. */
    private final java.util.function.Function<String, List<String>> projectLister;

    /** Upper bound on projects asked about per tenant and per check call (fail closed beyond it). */
    static final int MAX_PROJECTS = 200;
    private static final int CHECK_BATCH = 500;

    private final Map<String, Cached<Optional<NumuIdentity.Resolved>>> credentials = new ConcurrentHashMap<>();

    public NumuPrincipalResolver(NumuIdentity identity, NumuScheme scheme, Clock clock, Duration ttl) {
        this(identity, scheme, clock, ttl, t -> List.of());
    }

    public NumuPrincipalResolver(NumuIdentity identity, NumuScheme scheme, Clock clock, Duration ttl,
            java.util.function.Function<String, List<String>> projectLister) {
        this.projectLister = projectLister;
        this.identity = identity;
        this.scheme = scheme;
        this.clock = clock;
        this.ttl = ttl;
    }

    public NumuPrincipalResolver(NumuIdentity identity) {
        this(identity, new NumuScheme(), Clock.systemUTC(), Duration.ofSeconds(30));
    }

    /**
     * @param credential bearer JWT or PAT, without the "Bearer " prefix
     * @param tenantId   the tenant the caller asks to act in
     */
    public Principal resolve(String credential, String tenantId) {
        if (credential == null || credential.isBlank()) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        NumuIdentity.Resolved who = resolveCredential(credential)
                .orElseThrow(() -> new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated"));

        if (!scheme.validSegment(tenantId)) {
            throw new ApiException(ApiException.Code.FORBIDDEN, "No access to this tenant");
        }
        Grants g = grantsFor(who, tenantId);
        if (g.tenantWide().isEmpty() && g.projects().isEmpty()) {
            throw new ApiException(ApiException.Code.FORBIDDEN, "No access to this tenant");
        }
        String user = (who.type() == NumuIdentity.PrincipalType.SERVICE_ACCOUNT ? "svc-" : "u-") + who.id();
        return new Principal(tenantId, user, Set.of(), g.tenantWide(), g.projects());
    }

    private Optional<NumuIdentity.Resolved> resolveCredential(String credential) {
        String key = sha256(credential);
        Cached<Optional<NumuIdentity.Resolved>> hit = credentials.get(key);
        if (hit != null && hit.fresh(clock, ttl)) {
            return hit.value;
        }
        Optional<NumuIdentity.Resolved> fresh;
        try {
            fresh = identity.resolve(credential);
        } catch (NumuIdentity.IdentityUnavailableException e) {
            throw unavailable();
        }
        // Only valid credentials are cached: an invalid one must be re-checked (it may have just been issued).
        if (fresh.isPresent()) {
            put(credentials, key, fresh);
        }
        return fresh;
    }

    private record Grants(Set<Permission> tenantWide, Map<String, Set<Permission>> projects) {
    }

    private final Map<String, Cached<Grants>> grantCache = new ConcurrentHashMap<>();

    private Grants grantsFor(NumuIdentity.Resolved who, String tenantId) {
        String key = who.type() + "|" + who.id() + "|" + tenantId;
        Cached<Grants> hit = grantCache.get(key);
        if (hit != null && hit.fresh(clock, ttl)) {
            return hit.value;
        }
        List<Permission> all = List.of(Permission.values());
        // Project ids that are not valid path segments cannot be asked about, so they simply get no grants.
        List<String> projectIds = new ArrayList<>();
        for (String id : projectLister.apply(tenantId)) {
            if (scheme.validSegment(id)) {
                projectIds.add(id);
            }
        }
        if (projectIds.size() > MAX_PROJECTS) {
            projectIds = projectIds.subList(0, MAX_PROJECTS);
        }
        List<String> paths = new ArrayList<>();
        paths.add(scheme.tenantPath(tenantId));
        for (String id : projectIds) {
            paths.add(scheme.projectPath(tenantId, id));
        }
        List<NumuIdentity.Check> checks = new ArrayList<>();
        for (String path : paths) {
            for (Permission p : all) {
                checks.add(new NumuIdentity.Check(path, scheme.code(p)));
            }
        }
        List<Boolean> answers = new ArrayList<>();
        for (int from = 0; from < checks.size(); from += CHECK_BATCH) {
            List<NumuIdentity.Check> part = checks.subList(from, Math.min(checks.size(), from + CHECK_BATCH));
            List<Boolean> a;
            try {
                a = identity.check(who, part);
            } catch (NumuIdentity.IdentityUnavailableException e) {
                throw unavailable();
            }
            if (a == null || a.size() != part.size()) {
                throw unavailable(); // a malformed answer is not an answer
            }
            answers.addAll(a);
        }
        Set<Permission> tenantWide = permissionsAt(all, answers, 0);
        Map<String, Set<Permission>> projects = new java.util.LinkedHashMap<>();
        for (int i = 0; i < projectIds.size(); i++) {
            Set<Permission> g = permissionsAt(all, answers, (i + 1) * all.size());
            // Numu inheritance already folds tenant grants in; keeping only extras keeps the Principal small.
            if (!g.isEmpty() && !g.equals(tenantWide)) {
                projects.put(projectIds.get(i), g);
            }
        }
        Grants result = new Grants(Set.copyOf(tenantWide), Map.copyOf(projects));
        put(grantCache, key, result);
        return result;
    }

    /** ADMIN at a scope implies every permission there. */
    private static Set<Permission> permissionsAt(List<Permission> all, List<Boolean> answers, int offset) {
        Set<Permission> granted = EnumSet.noneOf(Permission.class);
        for (int i = 0; i < all.size(); i++) {
            if (Boolean.TRUE.equals(answers.get(offset + i))) {
                granted.add(all.get(i));
            }
        }
        return granted.contains(Permission.ADMIN) ? EnumSet.allOf(Permission.class) : granted;
    }

    private <V> void put(Map<String, Cached<V>> cache, String key, V value) {
        if (ttl.isZero() || ttl.isNegative()) {
            return;
        }
        if (cache.size() >= MAX_CACHE) {
            cache.clear(); // crude but bounded; the cache is an optimisation, not a source of truth
        }
        cache.put(key, new Cached<>(value, clock.instant()));
    }

    private static ApiException unavailable() {
        return new ApiException(ApiException.Code.UNAVAILABLE, "Identity service unavailable");
    }

    private static String sha256(String s) {
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(s.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private record Cached<V>(V value, Instant at) {
        boolean fresh(Clock clock, Duration ttl) {
            return at.plus(ttl).isAfter(clock.instant());
        }
    }
}
