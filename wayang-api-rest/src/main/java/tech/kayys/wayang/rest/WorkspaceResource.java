package tech.kayys.wayang.rest;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.project.WorkspaceService;

/**
 * A project as a portable bundle. Export is a read; import is a write and is a dry run unless {@code options.dryRun} is false.
 * Secret values never travel in a bundle (see {@code POST /api/v1/secrets/archive/export} for those).
 */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class WorkspaceResource {

    @Inject
    WorkspaceConfig workspace;
    @Inject
    Principals principals;

    @GET
    @Path("projects/{id}/export")
    public Map<String, Object> export(@PathParam("id") String id, @QueryParam("includeTenantConfig") boolean includeTenantConfig,
            @QueryParam("only") String only) {
        return workspace.service().export(principals.current(), id, includeTenantConfig, false,
                only == null || only.isBlank() ? null : java.util.Arrays.asList(only.split(",")));
    }

    @POST
    @Path("workspace/import")
    @SuppressWarnings("unchecked")
    public WorkspaceService.Report importBundle(Map<String, Object> body) {
        if (body == null || !(body.get("bundle") instanceof Map<?, ?> bundle)) {
            throw ApiException.badRequest("Body must be {bundle: {...}, options: {...}}");
        }
        Map<String, Object> options = body.get("options") instanceof Map<?, ?> o ? (Map<String, Object>) o : null;
        return workspace.service().importBundle(principals.current(), (Map<String, Object>) bundle, WorkspaceService.Options.of(options));
    }
}
