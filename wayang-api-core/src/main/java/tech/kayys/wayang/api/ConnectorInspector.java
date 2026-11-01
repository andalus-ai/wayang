package tech.kayys.wayang.api;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Read-only look at what a connector offers, for the Studio's node editor. Today: the operations of a SOAP service described by a WSDL.
 * It performs one outbound GET of the WSDL (through the same private-network guard as the connector itself) and never calls an operation,
 * so it needs DEFINITION_WRITE (a designer) rather than read, and credentials are never accepted here.
 */
public final class ConnectorInspector {

    private final Map<String, ServiceHandler.Connector> connectors;
    private final RateLimiter limiter;

    public ConnectorInspector(Map<String, ServiceHandler.Connector> connectors) {
        this(connectors, null);
    }

    /** @param limiter shared across requests (the REST layer keeps one); null for no limit */
    public ConnectorInspector(Map<String, ServiceHandler.Connector> connectors, RateLimiter limiter) {
        this.connectors = connectors;
        this.limiter = limiter;
    }

    /** @param req {@code {connector: "soap", config: {wsdl, endpoint?, port?}}} */
    public Map<String, Object> describe(Principal p, Map<String, Object> req) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.canAnywhere(Permission.DEFINITION_WRITE)) {
            throw ApiException.forbidden(Permission.DEFINITION_WRITE);
        }
        if (req == null || !"soap".equals(req.get("connector"))) {
            throw ApiException.badRequest("Only the 'soap' connector can be described");
        }
        if (!(req.get("config") instanceof Map<?, ?> in) || !(in.get("wsdl") instanceof String wsdl) || wsdl.isBlank()) {
            throw ApiException.badRequest("config.wsdl is required");
        }
        if (limiter != null) {
            limiter.check(p);
        }
        ServiceHandler.Connector soap = connectors.get("soap");
        if (soap == null) {
            throw ApiException.badRequest("The soap connector is not available");
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("wsdl", wsdl); // only these keys: no credentials, headers or operation reach the connector
        for (String k : new String[] { "endpoint", "port" }) {
            if (in.get(k) instanceof String s && !s.isBlank()) {
                cfg.put(k, s);
            }
        }
        cfg.put("mode", "describe");
        try {
            return soap.call(cfg, Map.of());
        } catch (ServiceHandler.ConnectorException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            throw ApiException.badRequest("Could not read the WSDL: " + e.getClass().getSimpleName());
        }
    }
}
