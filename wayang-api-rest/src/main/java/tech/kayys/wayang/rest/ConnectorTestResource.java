package tech.kayys.wayang.rest;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ConnectorTester;

/** "Test this node" for the Studio: a real call, read-only operations only, explicit confirm required. */
@Path("/api/v1/connectors")
@Blocking
public class ConnectorTestResource {

    /** Shared by the describe and test endpoints: at most 20 outbound calls a minute per user, per process. */
    static final tech.kayys.wayang.api.RateLimiter LIMIT = Limits.CONNECTOR_CALLS;

    @Inject
    Principals principals;

    @Inject
    ConnectorsConfig connectors;

    @Inject
    OAuthConfig secrets;

    @POST
    @Path("test")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> test(Map<String, Object> body) {
        return new ConnectorTester(connectors.all(), secrets.resolver(), LIMIT).test(principals.current(), body);
    }
}
