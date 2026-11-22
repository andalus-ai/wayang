package tech.kayys.wayang.endpoints;

import java.time.Clock;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WayangExtension;

/**
 * ESB-style endpoints that need no third-party library: today the file system ({@code fs-read}, {@code fs-write}, {@code fs-list}, {@code fs-move},
 * {@code fs-delete} and the {@code fs} trigger source), confined to {@code WAYANG_ENDPOINTS_FS_ROOT}. Loaded only when the profile enables ENDPOINTS.
 */
public final class EndpointsExtension implements WayangExtension {

    private final Map<String, ServiceHandler.Connector> connectors;
    private final Map<String, Poller> pollers;

    public EndpointsExtension() {
        this(FsRoot.fromEnvironment(), Clock.systemUTC());
    }

    EndpointsExtension(FsRoot root, Clock clock) {
        this.connectors = Map.of(
                "fs-read", new FsConnectors.Read(root),
                "fs-write", new FsConnectors.Write(root),
                "fs-list", new FsConnectors.ListFiles(root),
                "fs-move", new FsConnectors.Move(root),
                "fs-delete", new FsConnectors.Delete(root));
        this.pollers = Map.of("fs", new FsPoller(root, clock));
    }

    @Override
    public String id() {
        return "endpoints";
    }

    @Override
    public Set<Profile.Feature> requires() {
        return Set.of(Profile.Feature.ENDPOINTS);
    }

    @Override
    public Map<String, ServiceHandler.Connector> connectors() {
        return connectors;
    }

    @Override
    public Map<String, Poller> pollers() {
        return pollers;
    }

    @Override
    public List<ComponentDescriptor> components() {
        Map<String, Object> path = Map.of("type", "string", "description", "Path inside the endpoint directory");
        return List.of(
                component("fs-read", "Read file", "Read a file from the endpoint directory as text or base64", "files.read",
                        Map.of("path", path, "encoding", Map.of("type", "string", "enum", List.of("utf-8", "base64")),
                                "maxBytes", Map.of("type", "integer", "minimum", 1, "maximum", FsConnectors.HARD_MAX_BYTES)), List.of()),
                component("fs-write", "Write file", "Write a file into the endpoint directory (atomic; refuses to replace unless overwrite)", "files.write",
                        Map.of("path", path, "encoding", Map.of("type", "string", "enum", List.of("utf-8", "base64")),
                                "overwrite", Map.of("type", "boolean")), List.of()),
                component("fs-list", "List files", "List files of a directory in the endpoint directory", "files.list",
                        Map.of("path", path, "glob", Map.of("type", "string")), List.of()),
                component("fs-move", "Move file", "Move or rename a file inside the endpoint directory (for example to an archive folder)", "files.move",
                        Map.of("overwrite", Map.of("type", "boolean")), List.of()),
                component("fs-delete", "Delete file", "Delete one file (never a directory)", "files.delete", Map.of(), List.of()));
    }

    private ComponentDescriptor component(String connector, String title, String description, String capability, Map<String, Object> props,
            List<String> required) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        if (!required.isEmpty()) {
            schema.put("required", required);
        }
        schema.put("additionalProperties", true);
        List<Map<String, Object>> inputs = connectors.get(connector).inputs().stream().map(ConnectorInput::toMap).toList();
        return new ComponentDescriptor("wayang.endpoints." + connector, "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service", description,
                Set.of(Profile.Feature.ENDPOINTS), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of(capability), title, "endpoints", null,
                schema, Map.of("inputs", inputs));
    }
}
