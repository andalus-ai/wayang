package tech.kayys.wayang.api;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicLong;
import java.util.regex.Pattern;

/**
 * Records what authenticated callers change and what they are refused. Policy: every non-read request
 * (POST/PUT/PATCH/DELETE) and every 401/403 of an authenticated caller; reads are not logged. Unauthenticated requests are
 * not recorded (the tenant is unproven and anyone could flood the log). Recording never fails a request: an error
 * is counted in {@link #failures()}. Reading the log needs tenant-wide ADMIN.
 */
public final class AuditService {

    private static final Set<String> READS = Set.of("GET", "HEAD", "OPTIONS");
    private static final Pattern CREDENTIAL_PARAM = Pattern.compile("(?i).*(token|secret|key|state|code|password).*");
    private static final int MAX_ID = 200;
    public static final int MAX_LIST = 500;

    private final AuditStore store;
    private final Clock clock;
    private final AtomicLong failures = new AtomicLong();

    public AuditService(AuditStore store, Clock clock) {
        this.store = store;
        this.clock = clock;
    }

    /**
     * @param routeTemplate the matched route ({@code /api/v1/definitions/{kind}/{id}}), never the raw URL
     * @param pathParams    values of the template's parameters
     */
    public void record(Principal p, String method, String routeTemplate, Map<String, String> pathParams, int status) {
        try {
            if (p == null || method == null || routeTemplate == null) {
                return;
            }
            boolean denied = status == 401 || status == 403;
            if (READS.contains(method.toUpperCase(java.util.Locale.ROOT)) && !denied) {
                return;
            }
            Map<String, String> ids = new LinkedHashMap<>();
            if (pathParams != null) {
                pathParams.forEach((k, v) -> ids.put(k, CREDENTIAL_PARAM.matcher(k).matches() ? "***" : clip(v)));
            }
            String outcome = denied ? "DENIED" : status >= 400 ? "FAILED" : "OK";
            store.append(new AuditEntry(p.tenantId(), clock.instant(), p.userId(), method.toUpperCase(java.util.Locale.ROOT),
                    clip(routeTemplate), ids, status, outcome));
        } catch (RuntimeException e) {
            failures.incrementAndGet();
        }
    }

    /**
     * A run's node asked for a secret ({@code RESOLVE secret}). Actor is {@code run:<process>}; the entry holds the secret's name,
     * the node and the run, never a value. Recording never fails a run.
     */
    public void recordSecretRead(String tenant, String process, String runId, String nodeId, String name, boolean found) {
        try {
            if (tenant == null || name == null) {
                return;
            }
            Map<String, String> ids = new LinkedHashMap<>();
            ids.put("secret", clip(name));
            ids.put("node", clip(nodeId));
            ids.put("run", clip(runId));
            store.append(new AuditEntry(tenant, clock.instant(), "run:" + clip(process == null ? "" : process), "RESOLVE", "secret", ids,
                    found ? 200 : 404, found ? "OK" : "FAILED"));
        } catch (RuntimeException e) {
            failures.incrementAndGet();
        }
    }

    public List<AuditEntry> list(Principal p, int limit, java.time.Instant before) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.can(Permission.ADMIN)) {
            throw ApiException.forbidden(Permission.ADMIN);
        }
        return store.list(p.tenantId(), Math.max(1, Math.min(limit, MAX_LIST)), before);
    }

    public long failures() {
        return failures.get();
    }

    private static String clip(String s) {
        return s == null ? "" : s.length() > MAX_ID ? s.substring(0, MAX_ID) : s;
    }
}
