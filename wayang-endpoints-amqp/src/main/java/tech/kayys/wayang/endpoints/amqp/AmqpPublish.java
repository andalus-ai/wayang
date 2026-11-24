package tech.kayys.wayang.endpoints.amqp;

import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import tech.kayys.wayang.runtime.ConnectorInput;
import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Connector {@code amqp-publish}: publishes one persistent message to an exchange and waits for the broker's confirmation; a message that no queue
 * receives is an error (mandatory routing), not a silent loss. Node config: {@code host}, {@code port}, {@code tls}, {@code vhost}, {@code user},
 * {@code password}, {@code exchange} (see {@link Amqp}). Inputs: {@code body} (text, or an object/array sent as JSON; at most 1 MiB),
 * {@code routingKey}, {@code headers} (text values, at most 16), {@code messageId} (default random). Output: {@code exchange}, {@code routingKey},
 * {@code messageId}, {@code size}. Timeouts and connection failures are retryable (a retry can duplicate: consumers should de-duplicate on
 * {@code messageId}); an unroutable message and every other failure are not.
 */
final class AmqpPublish implements ServiceHandler.Connector {

    private final Amqp amqp;

    AmqpPublish(Amqp amqp) {
        this.amqp = amqp;
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(ConnectorInput.required("body", "any", "The message: text, or an object/array sent as JSON"),
                ConnectorInput.optional("routingKey", "string", "Routing key (default empty)"),
                ConnectorInput.optional("headers", "object", "Message headers (text values)"),
                ConnectorInput.optional("messageId", "string", "Message id (default random); consumers can de-duplicate on it"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ConnectorException("amqp-publish needs node configuration (host, user, password, exchange)", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        Amqp.Target target = amqp.target(config);
        String exchange = amqp.exchange(config);
        String key = Amqp.routingKey(inputs.get("routingKey"));
        Object b = inputs.get("body");
        if (b == null) {
            throw new ConnectorException("body is needed", false);
        }
        boolean json = !(b instanceof String);
        byte[] body = (json ? Json.write(b) : (String) b).getBytes(StandardCharsets.UTF_8);
        if (body.length > Amqp.MAX_BODY_BYTES) {
            throw new ConnectorException("body is larger than " + Amqp.MAX_BODY_BYTES + " bytes", false);
        }
        Map<String, Object> headers = new LinkedHashMap<>();
        if (inputs.get("headers") instanceof Map<?, ?> h) {
            if (h.size() > 16) {
                throw new ConnectorException("at most 16 headers", false);
            }
            h.forEach((k, v) -> headers.put(String.valueOf(k), String.valueOf(v)));
        }
        String id = inputs.get("messageId") == null ? UUID.randomUUID().toString() : String.valueOf(inputs.get("messageId"));
        if (id.length() > 255) {
            throw new ConnectorException("messageId is longer than 255 characters", false);
        }
        try {
            amqp.publisher.publish(target, new Amqp.Message(exchange, key, body, json ? "application/json" : "text/plain", id, headers));
        } catch (Amqp.Unroutable e) {
            throw new ConnectorException("no queue received the message (check the exchange bindings and routingKey)", false);
        } catch (Exception e) {
            throw Amqp.fail("publishing failed", e, Amqp.transientFailure(e));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("exchange", exchange);
        out.put("routingKey", key);
        out.put("messageId", id);
        out.put("size", (long) body.length);
        return out;
    }
}
