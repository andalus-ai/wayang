package tech.kayys.wayang.endpoints.jms;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WayangExtension;

/** JMS as an endpoint: the {@code jms-send} connector. Loaded only when the profile enables ENDPOINTS_JMS. */
public final class JmsExtension implements WayangExtension {

    private final Map<String, ServiceHandler.Connector> connectors;

    public JmsExtension() {
        this(Jms.fromEnvironment());
    }

    JmsExtension(Jms jms) {
        this.connectors = Map.of("jms-send", new JmsSend(jms));
    }

    @Override
    public String id() {
        return "wayang-endpoints-jms";
    }

    @Override
    public Set<Profile.Feature> requires() {
        return Set.of(Profile.Feature.ENDPOINTS_JMS);
    }

    @Override
    public Map<String, ServiceHandler.Connector> connectors() {
        return connectors;
    }

    @Override
    public List<ComponentDescriptor> components() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("host", Map.of("type", "string", "description", "must be permitted by the operator"));
        props.put("port", Map.of("type", "integer", "default", 61616));
        props.put("tls", Map.of("type", "boolean", "default", true));
        props.put("user", Map.of("type", "string"));
        props.put("password", Map.of("type", "string", "description", "use ${secret:NAME}"));
        props.put("destination", Map.of("type", "string", "description", "must start with a prefix permitted by the operator"));
        props.put("destinationType", Map.of("type", "string", "enum", List.of("queue", "topic"), "default", "queue"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("host", "user", "password", "destination"));
        schema.put("additionalProperties", true);
        List<Map<String, Object>> inputs = connectors.get("jms-send").inputs().stream().map(ConnectorInput::toMap).toList();
        return List.of(new ComponentDescriptor("wayang.endpoints.jms-send", "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service",
                "Send one persistent text message to a JMS queue or topic (Apache ActiveMQ Artemis); brokers and destinations must be permitted by the operator",
                Set.of(Profile.Feature.ENDPOINTS_JMS), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of("jms.send"), "JMS send", "endpoints", null,
                schema, Map.of("inputs", inputs)));
    }
}
