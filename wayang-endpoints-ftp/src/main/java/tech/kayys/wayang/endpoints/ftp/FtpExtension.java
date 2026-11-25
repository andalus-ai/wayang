package tech.kayys.wayang.endpoints.ftp;

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

/** FTP as an endpoint: {@code ftp-list/read/write/delete} and the {@code ftp} trigger source. Loaded only when the profile enables ENDPOINTS_FTP. */
public final class FtpExtension implements WayangExtension {

    private final Map<String, ServiceHandler.Connector> connectors;
    private final Map<String, Poller> pollers;

    public FtpExtension() {
        this(Ftp.fromEnvironment(), Clock.systemUTC());
    }

    FtpExtension(Ftp ftp, Clock clock) {
        this.connectors = Map.of("ftp-list", new FtpConnectors.ListFiles(ftp), "ftp-read", new FtpConnectors.Read(ftp),
                "ftp-write", new FtpConnectors.Write(ftp), "ftp-delete", new FtpConnectors.Delete(ftp));
        this.pollers = Map.of("ftp", new FtpPoller(ftp, clock));
    }

    @Override
    public String id() {
        return "wayang-endpoints-ftp";
    }

    @Override
    public Set<Profile.Feature> requires() {
        return Set.of(Profile.Feature.ENDPOINTS_FTP);
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
        props.put("port", Map.of("type", "integer", "default", 21));
        props.put("user", Map.of("type", "string"));
        props.put("password", Map.of("type", "string", "description", "use ${secret:NAME}"));
        props.put("tls", Map.of("type", "boolean", "default", true, "description", "explicit FTPS; false (plain FTP) needs operator opt-in"));
        return List.of(
                component("ftp-list", "FTP list", "List the files of a remote directory", "ftp.list", props),
                component("ftp-read", "FTP read", "Read a remote file as text or base64", "ftp.read", props),
                component("ftp-write", "FTP write", "Write a remote file (via a temporary name; refuses to replace unless overwrite)", "ftp.write", props),
                component("ftp-delete", "FTP delete", "Delete one remote file (never a directory)", "ftp.delete", props));
    }

    private ComponentDescriptor component(String connector, String title, String description, String capability, Map<String, Object> props) {
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("host", "user", "password"));
        schema.put("additionalProperties", true);
        List<Map<String, Object>> inputs = connectors.get(connector).inputs().stream().map(ConnectorInput::toMap).toList();
        return new ComponentDescriptor("wayang.endpoints." + connector, "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service", description,
                Set.of(Profile.Feature.ENDPOINTS_FTP), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of(capability), title, "endpoints", null,
                schema, Map.of("inputs", inputs));
    }
}
