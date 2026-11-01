package tech.kayys.wayang.rest;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.TemplateService;
import tech.kayys.wayang.api.WorkspaceService;

/** Starter projects: {@code GET /templates}, {@code POST /templates/{id}/instantiate {params, dryRun}} (a dry run unless {@code dryRun} is false). */
@Path("/api/v1/templates")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class TemplateResource {

    @Inject
    WorkspaceConfig workspace;
    @Inject
    Principals principals;

    @GET
    public List<TemplateService.Info> list() {
        return new TemplateService(workspace.service()).list();
    }

    @POST
    @Path("{id}/instantiate")
    @SuppressWarnings("unchecked")
    public WorkspaceService.Report instantiate(@PathParam("id") String id, Map<String, Object> body) {
        Map<String, Object> b = body == null ? Map.of() : body;
        Map<String, Object> params = b.get("params") instanceof Map<?, ?> m ? (Map<String, Object>) m : Map.of();
        return new TemplateService(workspace.service()).instantiate(principals.current(), id, params, !Boolean.FALSE.equals(b.get("dryRun")));
    }
}
