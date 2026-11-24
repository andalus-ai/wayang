package tech.kayys.wayang.endpoints.sftp;

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

/** SFTP as an endpoint: {@code sftp-list/read/write/delete} and the {@code sftp} trigger source. Loaded only when the profile enables ENDPOINTS_SFTP. */
public final class SftpExtension implements WayangExtension {

    private final Map<String, ServiceHandler.Connector> connectors;
    private final Map<String, Poller> pollers;

    public SftpExtension() {
        this(Sftp.fromEnvironment(), Clock.systemUTC());
    }

    SftpExtension(Sftp sftp, Clock clock) {
        this.connectors = Map.of("sftp-list", new SftpConnectors.ListFiles(sftp), "sftp-read", new SftpConnectors.Read(sftp),
                "sftp-write", new SftpConnectors.Write(sftp), "sftp-delete", new SftpConnectors.Delete(sftp));
        this.pollers = Map.of("sftp", new SftpPoller(sftp, clock));
    }

    @Override
    public String id() {
        return "endpoints-sftp";
    }

    @Override
    public Set<Profile.Feature> requires() {
        return Set.of(Profile.Feature.ENDPOINTS_SFTP);
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
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("host", Map.of("type", "string", "description", "must be permitted by the operator"));
        props.put("port", Map.of("type", "integer", "default", 22));
        props.put("user", Map.of("type", "string"));
        props.put("password", Map.of("type", "string", "description", "use ${secret:NAME}"));
        props.put("privateKey", Map.of("type", "string", "description", "PEM text; use ${secret:NAME}"));
        props.put("passphrase", Map.of("type", "string", "description", "use ${secret:NAME}"));
        props.put("hostKey", Map.of("type", "string", "description", "the server's SHA256:... fingerprint (mandatory)"));
        return List.of(
                component("sftp-list", "SFTP list", "List the files of a remote directory", "sftp.list", props),
                component("sftp-read", "SFTP read", "Read a remote file as text or base64", "sftp.read", props),
                component("sftp-write", "SFTP write", "Write a remote file (via a temporary name; refuses to replace unless overwrite)", "sftp.write", props),
                component("sftp-delete", "SFTP delete", "Delete one remote file (never a directory)", "sftp.delete", props));
    }

    private ComponentDescriptor component(String connector, String title, String description, String capability, Map<String, Object> props) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("host", "user", "hostKey"));
        schema.put("additionalProperties", true);
        List<Map<String, Object>> inputs = connectors.get(connector).inputs().stream().map(ConnectorInput::toMap).toList();
        return new ComponentDescriptor("wayang.endpoints." + connector, "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service", description,
                Set.of(Profile.Feature.ENDPOINTS_SFTP), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of(capability), title, "endpoints", null,
                schema, Map.of("inputs", inputs));
    }
}
