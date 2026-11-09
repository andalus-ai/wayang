package tech.kayys.wayang.api.connector;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.Permission;
import tech.kayys.wayang.api.Principal;
import tech.kayys.wayang.api.RateLimiter;
import tech.kayys.wayang.api.definition.Studio;

import java.util.LinkedHashMap;
import java.util.Map;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Read-only look at what a connector offers, for the Studio's node editor. The operations of a SOAP service (from its WSDL) or of an OpenAPI/Swagger document (the {@code openapi} connector's listOperations).
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

    /** @param req {@code {connector: "soap", config: {wsdl, endpoint?, port?}}} or {@code {connector: "openapi", config: {spec | specUrl, methods?, includeTags?, ...}}} */
    public Map<String, Object> describe(Principal p, Map<String, Object> req) {
        if (p == null) {
            throw new ApiException(ApiException.Code.UNAUTHORIZED, "Not authenticated");
        }
        if (!p.canAnywhere(Permission.DEFINITION_WRITE)) {
            throw ApiException.forbidden(Permission.DEFINITION_WRITE);
        }
        String kind = req == null ? null : (String) (req.get("connector") instanceof String c ? c : null);
        if (!"soap".equals(kind) && !"openapi".equals(kind)) {
            throw ApiException.badRequest("Only the 'soap' and 'openapi' connectors can be described");
        }
        if (!(req.get("config") instanceof Map<?, ?> in)) {
            throw ApiException.badRequest("config is required");
        }
        Map<String, Object> cfg = new LinkedHashMap<>();
        if ("soap".equals(kind)) {
            if (!(in.get("wsdl") instanceof String wsdl) || wsdl.isBlank()) {
                throw ApiException.badRequest("config.wsdl is required");
            }
            cfg.put("wsdl", wsdl); // only these keys: no credentials, headers or operation reach the connector
            for (String k : new String[] { "endpoint", "port" }) {
                if (in.get(k) instanceof String s && !s.isBlank()) {
                    cfg.put(k, s);
                }
            }
            cfg.put("mode", "describe");
        } else {
            boolean spec = in.get("spec") instanceof String s1 && !s1.isBlank();
            boolean url = in.get("specUrl") instanceof String s2 && !s2.isBlank();
            if (spec == url) {
                throw ApiException.badRequest("Give config.spec or config.specUrl (one of them)");
            }
            // The document and the filters only: never headers, query, baseUrl or an operation to call.
            for (String k : new String[] { "spec", "specUrl" }) {
                if (in.get(k) instanceof String s && !s.isBlank()) {
                    cfg.put(k, s);
                }
            }
            for (String k : new String[] { "methods", "includeTags", "excludeTags", "includePaths", "excludePaths" }) {
                if (in.get(k) instanceof java.util.List<?> l && !l.isEmpty()) {
                    cfg.put(k, l);
                }
            }
            cfg.put("operation", "listOperations");
        }
        if (limiter != null) {
            limiter.check(p);
        }
        ServiceHandler.Connector connector = connectors.get(kind);
        if (connector == null) {
            throw ApiException.badRequest("The " + kind + " connector is not available");
        }
        try {
            return connector.call(cfg, Map.of());
        } catch (ServiceHandler.ConnectorException e) {
            throw ApiException.badRequest(e.getMessage());
        } catch (Exception e) {
            throw ApiException.badRequest("Could not read the " + ("soap".equals(kind) ? "WSDL" : "specification") + ": " + e.getClass().getSimpleName());
        }
    }
}
