package tech.kayys.wayang.rest;

import java.util.List;
import java.util.Locale;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.HeaderParam;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.DefinitionKind;
import tech.kayys.wayang.api.DefinitionSummary;
import tech.kayys.wayang.api.StoredDefinition;
import tech.kayys.wayang.api.ValidationReport;
import tech.kayys.wayang.api.WayangApi;

/**
 * Definitions of every kind: {@code kind} is one of {@code process},
 * {@code decision}, {@code agent}, {@code pipeline}. Drafts are edited with
 * optimistic concurrency: send the revision you loaded in {@code If-Match}
 * (omit it only to create); a stale revision answers 409.
 */
@Path("/api/v1/definitions/{kind}")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class DefinitionResource {

    @Inject
    WayangApi api;
    @Inject
    Principals principals;

    @GET
    public List<DefinitionSummary> list(@PathParam("kind") String kind, @QueryParam("projectId") String projectId) {
        return projectId == null || projectId.isBlank() ? api.list(principals.current(), kind(kind))
                : api.list(principals.current(), kind(kind), projectId);
    }

    @GET
    @Path("{id}/draft")
    public StoredDefinition draft(@PathParam("kind") String kind, @PathParam("id") String id) {
        return api.getDraft(principals.current(), kind(kind), id);
    }

    @PUT
    @Path("{id}/draft")
    public StoredDefinition save(@PathParam("kind") String kind, @PathParam("id") String id,
            @HeaderParam("If-Match") String ifMatch, Map<String, Object> doc) {
        return api.saveDraft(principals.current(), kind(kind), id, doc, revision(ifMatch));
    }

    @DELETE
    @Path("{id}/draft")
    public Response delete(@PathParam("kind") String kind, @PathParam("id") String id) {
        return api.deleteDraft(principals.current(), kind(kind), id) ? Response.noContent().build()
                : Response.status(404).build();
    }

    @POST
    @Path("{id}/publish")
    public StoredDefinition publish(@PathParam("kind") String kind, @PathParam("id") String id) {
        return api.publish(principals.current(), kind(kind), id);
    }

    @GET
    @Path("{id}/versions/latest")
    public StoredDefinition latest(@PathParam("kind") String kind, @PathParam("id") String id) {
        return api.getVersion(principals.current(), kind(kind), id, null);
    }

    @GET
    @Path("{id}/versions/{version}")
    public StoredDefinition version(@PathParam("kind") String kind, @PathParam("id") String id,
            @PathParam("version") int version) {
        return api.getVersion(principals.current(), kind(kind), id, version);
    }

    @POST
    @Path("validate")
    public ValidationReport validate(@PathParam("kind") String kind, Map<String, Object> doc) {
        return api.validate(principals.current(), kind(kind), doc);
    }

    @GET
    @Path("{id}/bpmn")
    @Produces(MediaType.APPLICATION_XML)
    public String exportBpmn(@PathParam("kind") String kind, @PathParam("id") String id,
            @QueryParam("version") Integer version) {
        if (kind(kind) != DefinitionKind.PROCESS) {
            throw ApiException.badRequest("Only processes have a BPMN form");
        }
        return api.exportBpmn(principals.current(), id, version);
    }

    @POST
    @Path("import-bpmn")
    @Consumes(MediaType.APPLICATION_XML)
    public Map<String, Object> importBpmn(@PathParam("kind") String kind, String xml) {
        if (kind(kind) != DefinitionKind.PROCESS) {
            throw ApiException.badRequest("Only processes have a BPMN form");
        }
        return api.importBpmn(principals.current(), xml);
    }

    static DefinitionKind kind(String s) {
        try {
            return DefinitionKind.valueOf(s.toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw ApiException.notFound("Definition kind '" + s + "'");
        }
    }

    private static Long revision(String ifMatch) {
        if (ifMatch == null || ifMatch.isBlank()) {
            return null;
        }
        try {
            return Long.parseLong(ifMatch.replace("\"", "").trim());
        } catch (NumberFormatException e) {
            throw ApiException.badRequest("If-Match must be the draft revision number");
        }
    }
}
