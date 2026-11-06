package tech.kayys.wayang.rest;

import java.util.List;
import java.util.Map;

import jakarta.enterprise.context.ApplicationScoped;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.GET;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.PathParam;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;
import jakarta.ws.rs.core.Response;
import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.runtime.ComponentConfigValidator;
import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ComponentRegistry;
import tech.kayys.wayang.runtime.Extensions;
import tech.kayys.wayang.runtime.Profile;

/** Read-only profile-filtered component metadata and configuration validation. */
@Path("/api/v1/catalog/components")
@ApplicationScoped
@Produces(MediaType.APPLICATION_JSON)
@Consumes(MediaType.APPLICATION_JSON)
public class ComponentCatalogResource {
    public record ConfigValidation(boolean valid, List<ComponentConfigValidator.Violation> violations) {
        public ConfigValidation {
            violations = List.copyOf(violations);
        }
    }

    private volatile ComponentRegistry catalog;

    private ComponentRegistry catalog() {
        ComponentRegistry current = catalog;
        if (current == null) {
            synchronized (this) {
                current = catalog;
                if (current == null) {
                    Profile profile = Profile.fromEnvironment();
                    current = ComponentRegistry.standard(profile, Extensions.load(profile));
                    catalog = current;
                }
            }
        }
        return current;
    }

    @GET
    public List<ComponentDescriptor> list() {
        return catalog().all();
    }

    /** Validate a component's config against the exact descriptor exposed by this catalog. */
    @POST
    @Path("{id}/validate")
    public Response validate(@PathParam("id") String id, Map<String, Object> config) {
        ComponentDescriptor descriptor = catalog().find(id);
        if (descriptor == null) throw ApiException.notFound("Component '" + id + "'");
        List<ComponentConfigValidator.Violation> violations =
                ComponentConfigValidator.validate(descriptor, config == null ? Map.of() : config);
        ConfigValidation result = new ConfigValidation(violations.isEmpty(), violations);
        return Response.status(result.valid() ? Response.Status.OK : 422).entity(result).build();
    }
}
