package tech.kayys.wayang.rest;

import java.util.Map;

import jakarta.inject.Inject;
import jakarta.ws.rs.Consumes;
import jakarta.ws.rs.POST;
import jakarta.ws.rs.Path;
import jakarta.ws.rs.Produces;
import jakarta.ws.rs.core.MediaType;

import io.smallrye.common.annotation.Blocking;
import tech.kayys.wayang.api.ConnectorInspector;

/** Lists the operations of a SOAP service from its WSDL, for the Studio. Needs DEFINITION_WRITE; calls nothing. */
@Path("/api/v1/connectors")
@Blocking
public class ConnectorInspectResource {

    /** Shared by the describe and test endpoints: at most 20 outbound calls a minute per user, per process. */
    static final tech.kayys.wayang.api.RateLimiter LIMIT = Limits.CONNECTOR_CALLS;

    @Inject
    Principals principals;

    @Inject
    ConnectorsConfig connectors;

    @POST
    @Path("describe")
    @Consumes(MediaType.APPLICATION_JSON)
    @Produces(MediaType.APPLICATION_JSON)
    public Map<String, Object> describe(Map<String, Object> body) {
        return new ConnectorInspector(connectors.all(), LIMIT).describe(principals.current(), body);
    }
}
