package tech.kayys.wayang.mcp;

import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.connectors.UrlGuard;
import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WayangExtension;

/** Adds the {@code openapi} connector: call any operation of a Swagger/OpenAPI document by giving only the document. Always loaded when on the classpath. */
public final class OpenApiExtension implements WayangExtension {

    @Override
    public String id() {
        return "openapi";
    }

    @Override
    public Map<String, ServiceHandler.Connector> connectors() {
        UrlGuard guard = new UrlGuard("true".equalsIgnoreCase(System.getenv("WAYANG_CONNECTORS_ALLOW_PRIVATE")), hosts());
        return Map.of("openapi", new OpenApiConnector(guard));
    }

    @Override
    public List<ComponentDescriptor> components() {
        return List.of(new ComponentDescriptor("wayang.mcp.openapi", "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service",
                "Call an operation of an OpenAPI/Swagger document", Set.of(Profile.Feature.SERVICE), List.of(),
                ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of("http.openapi.call")));
    }

    private static Set<String> hosts() {
        String h = System.getenv("WAYANG_CONNECTORS_ALLOWED_HOSTS");
        if (h == null || h.isBlank()) {
            return Set.of();
        }
        return Set.copyOf(java.util.Arrays.stream(h.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
    }
}
