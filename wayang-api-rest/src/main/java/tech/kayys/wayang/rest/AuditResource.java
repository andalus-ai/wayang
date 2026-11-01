package tech.kayys.wayang.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ApiException;

/** The tenant's audit log, newest first. Tenant-wide ADMIN only. Page with {@code before} (ISO-8601 instant). */
@Path("/api/v1/audit")
@Blocking
public class AuditResource {

    @Inject
    AuditConfig audit;
    @Inject
    Principals principals;

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> list(@QueryParam("limit") Integer limit, @QueryParam("before") String before) {
        Instant b = null;
        if (before != null && !before.isBlank()) {
            try {
                b = Instant.parse(before);
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.badRequest("before must be an ISO-8601 instant like 2026-01-01T00:00:00Z");
            }
        }
        return audit.service().list(principals.current(), limit == null ? 100 : limit, b).stream().map(e -> e.toMap()).toList();
    }
}
