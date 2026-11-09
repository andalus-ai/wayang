package tech.kayys.wayang.api.audit;

import java.time.Instant;
import java.util.Map;

/**
 * One audited request: who did what to which resource and what came of it. Never holds bodies, query strings or
 * secret values; {@code ids} are the route's path parameters with credential-like ones masked.
 *
 * @param outcome {@code OK}, {@code DENIED} (401/403), {@code FAILED} (other 4xx/5xx)
 */
public record AuditEntry(String tenant, Instant time, String actor, String method, String route, Map<String, String> ids,
        int status, String outcome) {
    public AuditEntry {
        ids = ids == null ? Map.of() : Map.copyOf(ids);
    }

    public Map<String, Object> toMap() {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("time", time.toString());
        m.put("actor", actor);
        m.put("method", method);
        m.put("route", route);
        m.put("ids", ids);
        m.put("status", status);
        m.put("outcome", outcome);
        return m;
    }
}
