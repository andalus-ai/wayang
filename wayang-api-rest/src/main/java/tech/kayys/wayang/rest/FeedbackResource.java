package tech.kayys.wayang.rest;

import java.time.Instant;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PATCH;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ApiException;

/**
 * Feedback: any signed-in user sends a rating, comment, bug, idea or error report ({@code POST}); the tenant's ADMIN reads them
 * ({@code GET}, newest first, filter with {@code kind}, page with {@code before}). Credentials are masked before anything is stored.
 */
@Path("/api/v1/feedback")
@Blocking
public class FeedbackResource {

    @Inject
    FeedbackConfig feedback;
    @Inject
    Principals principals;

    @POST
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> submit(Map<String, Object> body) {
        return feedback.service().submit(principals.current(), body);
    }

    /** Open items per kind and thumbs up/down over the newest 500 entries; ADMIN only. */
    @GET
    @Path("/summary")
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> summary() {
        return feedback.service().summary(principals.current());
    }

    /** The newest feedback as a CSV download ({@code kind} filters, {@code limit} up to 500); ADMIN only. */
    @GET
    @Path("/export")
    @Produces("text/csv")
    public jakarta.ws.rs.core.Response export(@QueryParam("kind") String kind, @QueryParam("limit") Integer limit) {
        String csv = feedback.service().exportCsv(principals.current(), kind == null || kind.isBlank() ? null : kind, limit == null ? 500 : limit);
        return jakarta.ws.rs.core.Response.ok(csv).header("Content-Disposition", "attachment; filename=\"feedback.csv\"").build();
    }

    /** {@code {status: "open" | "done"}}: mark one entry as dealt with (or reopen it); ADMIN only. */
    @PATCH
    @Path("/{id}")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> status(@PathParam("id") String id, Map<String, Object> body) {
        return feedback.service().setStatus(principals.current(), id, body == null ? null : String.valueOf(body.get("status")));
    }

    @GET
    @Produces(MediaType.APPLICATION_JSON)
    public List<Map<String, Object>> list(@QueryParam("kind") String kind, @QueryParam("limit") Integer limit, @QueryParam("before") String before) {
        Instant b = null;
        if (before != null && !before.isBlank()) {
            try {
                b = Instant.parse(before);
            } catch (java.time.format.DateTimeParseException e) {
                throw ApiException.badRequest("before must be an ISO-8601 instant like 2026-01-01T00:00:00Z");
            }
        }
        return feedback.service().list(principals.current(), kind == null || kind.isBlank() ? null : kind, limit == null ? 100 : limit, b).stream()
                .map(e -> e.toMap()).toList();
    }

    /** Deletes the tenant's feedback older than {@code before} (ISO-8601, at least a day ago); ADMIN only. */
    @DELETE
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> purge(@QueryParam("before") String before) {
        Instant b;
        try {
            b = before == null || before.isBlank() ? null : Instant.parse(before);
        } catch (java.time.format.DateTimeParseException e) {
            throw ApiException.badRequest("before must be an ISO-8601 instant like 2026-01-01T00:00:00Z");
        }
        return feedback.service().purge(principals.current(), b);
    }
}
