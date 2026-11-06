package tech.kayys.wayang.rest;

import java.util.List;

import jakarta.ws.rs.GET;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ComponentRegistry;
import tech.kayys.wayang.runtime.Extensions;
import tech.kayys.wayang.runtime.Profile;

/** Read-only profile-filtered component metadata for Studio and release tooling. */
@Path("/api/v1/catalog/components")
@Produces(MediaType.APPLICATION_JSON)
public class ComponentCatalogResource {
    @GET
    public List<ComponentDescriptor> list() {
        Profile profile = Profile.fromEnvironment();
        return ComponentRegistry.standard(profile, Extensions.load(profile)).all();
    }
}
