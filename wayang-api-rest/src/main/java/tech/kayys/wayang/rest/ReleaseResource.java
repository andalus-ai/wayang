package tech.kayys.wayang.rest;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ReleaseService;

/**
 * From a designed project to something that runs without this server. {@code plan} says what a release would contain; the other
 * call downloads it as a zip (a Maven project). Built from published versions only; no triggers, no secret values.
 * Options: {@code target} (SERVICE | STANDALONE), {@code groupId}, {@code artifactId}, {@code version}, {@code package}, {@code port}.
 */
@Path("/api/v1/projects/{id}/release")
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class ReleaseResource {

    @Inject
    ReleaseConfig releases;
    @Inject
    Principals principals;

    @POST
    @Path("plan")
    @Produces(MediaType.APPLICATION_JSON)
    public ReleaseService.Plan plan(@PathParam("id") String id, Map<String, Object> options) {
        return releases.service().plan(principals.current(), id, options);
    }

    @POST
    @Produces("application/zip")
    public Response release(@PathParam("id") String id, Map<String, Object> options) {
        ReleaseService.Release r = releases.service().release(principals.current(), id, options);
        return Response.ok(r.zip()).header("Content-Disposition", "attachment; filename=\"" + r.filename() + "\"")
                .header("Cache-Control", "no-store").build();
    }
}
