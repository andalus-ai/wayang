package tech.kayys.wayang.rest;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.MapperService;

/** Dry-run of mapper specs and EIP pipelines for the Studio editor. No side effects, no connectors. */
@Path("/api/v1/mapper")
@Blocking
public class MapperResource {

    private static final MapperService SERVICE = new MapperService();

    @Inject
    Principals principals;

    @POST
    @Path("preview")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> preview(Map<String, Object> body) {
        return SERVICE.preview(principals.current(), body);
    }
}
