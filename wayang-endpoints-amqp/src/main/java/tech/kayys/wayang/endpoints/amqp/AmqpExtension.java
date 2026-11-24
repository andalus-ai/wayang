package tech.kayys.wayang.endpoints.amqp;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.runtime.ComponentDescriptor;
import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.Profile;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WayangExtension;

/** RabbitMQ / AMQP 0-9-1 as an endpoint: the {@code amqp-publish} connector. Loaded only when the profile enables ENDPOINTS_AMQP. */
public final class AmqpExtension implements WayangExtension {

    private final Map<String, ServiceHandler.Connector> connectors;

    public AmqpExtension() {
        this(Amqp.fromEnvironment());
    }

    AmqpExtension(Amqp amqp) {
        this.connectors = Map.of("amqp-publish", new AmqpPublish(amqp));
    }

    @Override
    public String id() {
        return "wayang-endpoints-amqp";
    }

    @Override
    public Set<Profile.Feature> requires() {
        return Set.of(Profile.Feature.ENDPOINTS_AMQP);
    }

    @Override
    public Map<String, ServiceHandler.Connector> connectors() {
        return connectors;
    }

    @Override
    public List<ComponentDescriptor> components() {
        Map<String, Object> props = new LinkedHashMap<>();
        props.put("host", Map.of("type", "string", "description", "must be permitted by the operator"));
        props.put("port", Map.of("type", "integer", "description", "default 5671 with TLS, 5672 without"));
        props.put("tls", Map.of("type", "boolean", "default", true));
        props.put("vhost", Map.of("type", "string", "default", "/"));
        props.put("user", Map.of("type", "string"));
        props.put("password", Map.of("type", "string", "description", "use ${secret:NAME}"));
        props.put("exchange", Map.of("type", "string", "description", "must start with a prefix permitted by the operator"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("host", "user", "password", "exchange"));
        schema.put("additionalProperties", true);
        List<Map<String, Object>> inputs = connectors.get("amqp-publish").inputs().stream().map(ConnectorInput::toMap).toList();
        return List.of(new ComponentDescriptor("wayang.endpoints.amqp-publish", "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service",
                "Publish one persistent message to a RabbitMQ (AMQP 0-9-1) exchange and wait for the broker's confirmation; brokers and exchanges must be permitted by the operator",
                Set.of(Profile.Feature.ENDPOINTS_AMQP), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of("amqp.publish"), "AMQP publish", "endpoints", null,
                schema, Map.of("inputs", inputs)));
    }
}
