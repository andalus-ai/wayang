package tech.kayys.wayang.rest;

import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.Project;
import tech.kayys.wayang.api.WayangApi;

/** Projects group definitions in the Studio, plus the node catalog that drives its palette and property panel. */
@Path("/api/v1")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class ProjectResource {

    @Inject
    WayangApi api;
    @Inject
    ConfigsConfig configs;
    @Inject
    Principals principals;

    @GET
    @Path("catalog/nodes")
    public List<Map<String, Object>> catalog() {
        return api.nodeCatalog(principals.current());
    }

    @GET
    @Path("projects")
    public List<Project> list() {
        return api.listProjects(principals.current());
    }

    @POST
    @Path("projects")
    public Response create(Map<String, Object> body) {
        Project p = api.createProject(principals.current(), str(body, "name"), str(body, "description"));
        return Response.status(201).entity(p).build();
    }

    @GET
    @Path("projects/{id}")
    public Project get(@PathParam("id") String id) {
        return api.getProject(principals.current(), id);
    }

    @PUT
    @Path("projects/{id}")
    public Project update(@PathParam("id") String id, Map<String, Object> body) {
        return api.updateProject(principals.current(), id, str(body, "name"), str(body, "description"));
    }

    @DELETE
    @Path("projects/{id}")
    public Response delete(@PathParam("id") String id) {
        api.deleteProject(principals.current(), id);
        configs.service().dropProject(principals.current(), id);
        return Response.noContent().build();
    }

    private static String str(Map<String, Object> body, String key) {
        return body != null && body.get(key) instanceof String s ? s : null;
    }
}
