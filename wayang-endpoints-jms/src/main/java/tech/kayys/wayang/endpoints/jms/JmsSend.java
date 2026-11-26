package tech.kayys.wayang.endpoints.jms;

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
 * Connector {@code jms-send}: sends one persistent text message to a queue (default) or topic and returns once the broker has accepted it. Node
 * config: {@code host}, {@code port} (default 61616), {@code tls}, {@code user}, {@code password}, {@code destination}, {@code destinationType}
 * ({@code queue}|{@code topic}); see {@link Jms}. Inputs: {@code body} (text, or an object/array sent as JSON; at most 1 MiB), {@code properties}
 * (text values, names are identifiers, at most 16), {@code correlationId}, {@code messageId} (a {@code messageId} property, default random).
 * Output: {@code destination}, {@code messageId}, {@code size}. Timeouts and connection failures are retryable (a retry can duplicate: consumers
 * should de-duplicate on {@code messageId}); every other failure is not. Receiving is not offered: Gamelan's poller contract has no
 * "processed" callback, so a consumed message could only be acknowledged before its run exists.
 */
final class JmsSend implements ServiceHandler.Connector {

    private final Jms jms;

    JmsSend(Jms jms) {
        this.jms = jms;
    }

    @Override
    public List<ConnectorInput> inputs() {
        return List.of(ConnectorInput.required("body", "any", "The message: text, or an object/array sent as JSON"),
                ConnectorInput.optional("properties", "object", "Message properties (text values)"),
                ConnectorInput.optional("correlationId", "string", "JMSCorrelationID"),
                ConnectorInput.optional("messageId", "string", "Sent as the messageId property (default random); consumers can de-duplicate on it"));
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ConnectorException("jms-send needs node configuration (host, user, password, destination)", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        Jms.Target target = jms.target(config);
        String destination = jms.destination(config);
        boolean topic = Jms.topic(config);
        Object b = inputs.get("body");
        if (b == null) {
            throw new ConnectorException("body is needed", false);
        }
        String text = b instanceof String s ? s : Json.write(b);
        int size = text.getBytes(StandardCharsets.UTF_8).length;
        if (size > Jms.MAX_BODY_BYTES) {
            throw new ConnectorException("body is larger than " + Jms.MAX_BODY_BYTES + " bytes", false);
        }
        Map<String, String> props = new LinkedHashMap<>();
        if (inputs.get("properties") instanceof Map<?, ?> h) {
            if (h.size() > 16) {
                throw new ConnectorException("at most 16 properties", false);
            }
            for (var e : h.entrySet()) {
                String k = String.valueOf(e.getKey());
                if (!Jms.propertyName(k) || k.equals("messageId")) {
                    throw new ConnectorException("property names are identifiers (letters, digits, _), and messageId is set by the messageId input", false);
                }
                props.put(k, String.valueOf(e.getValue()));
            }
        }
        String id = inputs.get("messageId") == null ? UUID.randomUUID().toString() : String.valueOf(inputs.get("messageId"));
        String corr = inputs.get("correlationId") == null ? null : String.valueOf(inputs.get("correlationId"));
        if (id.length() > 255 || (corr != null && corr.length() > 255)) {
            throw new ConnectorException("messageId and correlationId are at most 255 characters", false);
        }
        try {
            jms.sender.send(target, new Jms.Message(destination, topic, text, corr, id, props));
        } catch (Exception e) {
            throw Jms.fail("sending failed", e, Jms.transientFailure(e));
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("destination", destination);
        out.put("messageId", id);
        out.put("size", (long) size);
        return out;
    }
}
