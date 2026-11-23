package tech.kayys.wayang.endpoints.kafka;

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

/** Apache Kafka as an endpoint: the {@code kafka-publish} connector and the {@code kafka} trigger source. Loaded only when the profile enables ENDPOINTS_KAFKA. */
public final class KafkaExtension implements WayangExtension {

    private final Map<String, ServiceHandler.Connector> connectors;
    private final Map<String, Poller> pollers;

    public KafkaExtension() {
        this(Kafka.fromEnvironment());
    }

    KafkaExtension(Kafka kafka) {
        this.connectors = Map.of("kafka-publish", new KafkaPublish(kafka));
        this.pollers = Map.of("kafka", new KafkaPoller(kafka));
    }

    @Override
    public Set<Profile.Feature> requires() {
        return Set.of(Profile.Feature.ENDPOINTS_KAFKA);
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
        props.put("bootstrapServers", Map.of("type", "string", "description", "host:port, comma-separated; must be permitted by the operator"));
        props.put("topic", Map.of("type", "string", "description", "must start with a prefix permitted by the operator"));
        props.put("securityProtocol", Map.of("type", "string", "enum", List.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL")));
        props.put("saslMechanism", Map.of("type", "string", "enum", List.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512")));
        props.put("saslUsername", Map.of("type", "string"));
        props.put("saslPassword", Map.of("type", "string", "description", "use ${secret:NAME}"));
        Map<String, Object> schema = new LinkedHashMap<>();
        schema.put("type", "object");
        schema.put("properties", props);
        schema.put("required", List.of("bootstrapServers", "topic"));
        schema.put("additionalProperties", true);
        List<Map<String, Object>> inputs = connectors.get("kafka-publish").inputs().stream().map(ConnectorInput::toMap).toList();
        return List.of(new ComponentDescriptor("wayang.endpoints.kafka-publish", "0.0.1", ComponentDescriptor.Kind.CONNECTOR, "wayang-service",
                "Publish one record to an Apache Kafka topic and wait for the broker's acknowledgement; brokers and topics must be permitted by the operator",
                Set.of(Profile.Feature.ENDPOINTS_KAFKA), List.of(), ComponentDescriptor.IsolationLevel.IN_PROCESS, Set.of("kafka.publish"), "Kafka publish", "endpoints",
                null, schema, Map.of("inputs", inputs)));
    }
}
