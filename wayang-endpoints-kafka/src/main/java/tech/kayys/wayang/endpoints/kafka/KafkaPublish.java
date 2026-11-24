package tech.kayys.wayang.endpoints.kafka;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.errors.RetriableException;

import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Connector {@code kafka-publish}: sends one record and waits for the broker's acknowledgement from all in-sync replicas (idempotent
 * producer). Node config: {@code bootstrapServers}, {@code topic} (see {@link Kafka}). Inputs: {@code value} (text, or an object or array sent as
 * JSON; at most 1 MiB), {@code key} (optional text), {@code headers} (optional text values, at most 16). Output: {@code topic}, {@code partition},
 * {@code offset}, {@code timestamp}. A broker timeout or a retriable broker error is retryable; anything else is not.
 */
final class KafkaPublish implements ServiceHandler.Connector {

    private final Kafka kafka;

    KafkaPublish(Kafka kafka) {
        this.kafka = kafka;
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(ConnectorInput.required("value", "any", "The record value: text, or an object/array sent as JSON"),
                ConnectorInput.optional("key", "string", "The record key (records with the same key keep their order)"),
                ConnectorInput.optional("headers", "object", "Record headers (text values)"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws ServiceHandler.ConnectorException {
        throw new ServiceHandler.ConnectorException("kafka-publish needs node configuration (bootstrapServers, topic)", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        String topic = kafka.check(config);
        Object v = inputs.get("value");
        if (v == null) {
            throw new ServiceHandler.ConnectorException("value is needed", false);
        }
        String value = v instanceof String s ? s : Json.write(v);
        if (value.getBytes(StandardCharsets.UTF_8).length > Kafka.MAX_VALUE_BYTES) {
            throw new ServiceHandler.ConnectorException("value is larger than " + Kafka.MAX_VALUE_BYTES + " bytes", false);
        }
        String key = inputs.get("key") == null ? null : String.valueOf(inputs.get("key"));
        Properties p = kafka.base(config);
        p.put("key.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("value.serializer", "org.apache.kafka.common.serialization.StringSerializer");
        p.put("acks", "all");
        p.put("enable.idempotence", "true");
        p.put("linger.ms", "0");
        p.put("max.block.ms", "10000");
        p.put("delivery.timeout.ms", "15000");
        ProducerRecord<String, String> rec = new ProducerRecord<>(topic, null, key, value);
        if (inputs.get("headers") instanceof Map<?, ?> h) {
            if (h.size() > 16) {
                throw new ServiceHandler.ConnectorException("at most 16 headers", false);
            }
            h.forEach((k, hv) -> rec.headers().add(String.valueOf(k), String.valueOf(hv).getBytes(StandardCharsets.UTF_8)));
        }
        try (Producer<String, String> producer = kafka.clients.producer(p)) {
            RecordMetadata md = producer.send(rec).get(20, TimeUnit.SECONDS);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("topic", md.topic());
            out.put("partition", md.partition());
            out.put("offset", md.offset());
            out.put("timestamp", md.timestamp());
            return out;
        } catch (TimeoutException e) {
            throw Kafka.fail("the broker did not acknowledge in time", e, true);
        } catch (ExecutionException e) {
            Throwable c = e.getCause() == null ? e : e.getCause();
            throw Kafka.fail("the broker rejected the record", c, c instanceof RetriableException);
        } catch (org.apache.kafka.common.KafkaException e) {
            throw Kafka.fail("publishing failed", e, false);
        }
    }
}
