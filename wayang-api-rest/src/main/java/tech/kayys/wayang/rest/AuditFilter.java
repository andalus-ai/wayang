package tech.kayys.wayang.rest;

import java.io.IOException;
import java.util.LinkedHashMap;
import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.container.ContainerRequestContext;
import jakarta.ws.rs.container.ContainerResponseContext;
import jakarta.ws.rs.container.ContainerResponseFilter;
import jakarta.ws.rs.container.ResourceInfo;
import jakarta.ws.rs.core.Context;
import jakarta.ws.rs.ext.Provider;

/**
 * Feeds {@link tech.kayys.wayang.api.AuditService}: after each response, the route template (never the raw URL, so no query
 * string or token reaches the log), its path parameters and the final status. Only the authenticated principal that the
 * resource already resolved is used ({@link Principals#peek()}), so the filter never calls the identity service itself.
 */
@Provider
public class AuditFilter implements ContainerResponseFilter {

    @Inject
    AuditConfig audit;
    @Inject
    Principals principals;
    @Context
    ResourceInfo resource;

    @Override
    public void filter(ContainerRequestContext req, ContainerResponseContext res) throws IOException {
        try {
            if (resource == null || resource.getResourceClass() == null || resource.getResourceMethod() == null) {
                return;
            }
            Path cp = resource.getResourceClass().getAnnotation(Path.class);
            Path mp = resource.getResourceMethod().getAnnotation(Path.class);
            String route = (cp == null ? "" : cp.value()) + (mp == null ? "" : (mp.value().startsWith("/") ? "" : "/") + mp.value());
            if (!route.startsWith("/api/v1")) {
                return;
            }
            Map<String, String> params = new LinkedHashMap<>();
            req.getUriInfo().getPathParameters().forEach((k, v) -> params.put(k, v.isEmpty() ? "" : v.get(0)));
            audit.service().record(principals.peek(), req.getMethod(), route, params, res.getStatus());
        } catch (RuntimeException e) {
            // auditing must never break a response
        }
    }
}
