package tech.kayys.wayang.rest;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.DELETE;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.PUT;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.QueryParam;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ConfigEntry;

/**
 * Plain, non-secret settings ({@code ${config:NAME}}), for the tenant or for one project ({@code ?project=ID}). Values are returned:
 * this is not where credentials go (the API refuses names that look like one).
 */
@Path("/api/v1/config")
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
@Blocking
public class ConfigResource {

    @Inject
    ConfigsConfig config;
    @Inject
    Principals principals;

    @GET
    public List<Map<String, Object>> list(@QueryParam("project") String project) {
        return config.service().list(principals.current(), project).stream().map(ConfigResource::view).toList();
    }

    @PUT
    @Path("{key}")
    public Map<String, Object> put(@PathParam("key") String key, @QueryParam("project") String project, Map<String, Object> body) {
        Object v = body == null ? null : body.get("value");
        return view(config.service().put(principals.current(), project, key, v instanceof String s ? s : null));
    }

    @DELETE
    @Path("{key}")
    public Response delete(@PathParam("key") String key, @QueryParam("project") String project) {
        config.service().delete(principals.current(), project, key);
        return Response.noContent().build();
    }

    static Map<String, Object> view(ConfigEntry e) {
        Map<String, Object> m = new LinkedHashMap<>();
        m.put("key", e.key());
        m.put("value", e.value());
        m.put("scope", e.scope().isEmpty() ? "tenant" : "project");
        m.put("project", e.scope().isEmpty() ? null : e.scope());
        m.put("version", e.version());
        m.put("updatedAt", e.updatedAt().toString());
        m.put("updatedBy", e.updatedBy());
        return m;
    }
}
