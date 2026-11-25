package tech.kayys.wayang.endpoints.amqp;

import java.nio.ByteBuffer;
import java.nio.charset.CharacterCodingException;
import java.nio.charset.CodingErrorAction;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Trigger source {@code amqp-stream}: a run per new message of a RabbitMQ <b>stream</b> queue. Config: {@code host}, {@code port}, {@code tls},
 * {@code vhost}, {@code user}, {@code password}, {@code queue} (must start with a prefix in {@code WAYANG_ENDPOINTS_AMQP_STREAM_PREFIXES}),
 * {@code start} ({@code first}, the default, applied once) and {@code maxMessages} (1-100, default 50).
 *
 * <p>Why streams only: Gamelan's poller contract has no "processed" callback, so a classic queue could only be acknowledged before the run is
 * durably started (at-most-once, loses messages on a crash) or never (redelivered forever). A stream keeps its messages, so Wayang holds the
 * position itself: the cursor is {@code o:<next offset>}, the trigger is at-least-once and resumes exactly where it stopped, like the Kafka trigger.
 * If retention removed the saved offset the broker attaches at the first available message (messages in between are gone: size retention
 * accordingly). Event fields: {@code queue}, {@code offset}, {@code body} (UTF-8 text, cut at 64 KiB: {@code bodyTruncated}), {@code size},
 * {@code binary} (true when the body was not valid UTF-8; it is then empty), {@code messageId}, {@code contentType}.
 */
final class AmqpStreamPoller implements Poller {

    private final Amqp amqp;

    AmqpStreamPoller(Amqp amqp) {
        this.amqp = amqp;
    }

    @Override
    public Result poll(Map<String, Object> config, String cursor) throws Exception {
        Amqp.Target target = amqp.streamTarget(config);
        String queue = amqp.streamQueue(config);
        if (config.get("start") != null && !"first".equals(String.valueOf(config.get("start")))) {
            throw new ConnectorException("start must be first (the saved cursor decides afterwards)", false);
        }
        int max = 50;
        if (config.get("maxMessages") != null) {
            try {
                max = Integer.parseInt(String.valueOf(config.get("maxMessages")).trim());
            } catch (NumberFormatException e) {
                throw new ConnectorException("maxMessages must be a number from 1 to 100", false);
            }
            if (max < 1 || max > 100) {
                throw new ConnectorException("maxMessages must be a number from 1 to 100", false);
            }
        }
        long from = parse(cursor);
        List<Amqp.Delivery> got;
        try {
            got = amqp.reader.read(target, queue, from, max, 2000);
        } catch (Exception e) {
            throw Amqp.fail("reading the stream failed", e, Amqp.transientFailure(e));
        }
        List<Map<String, Object>> events = new ArrayList<>();
        long next = from;
        for (Amqp.Delivery d : got) {
            if (events.size() >= max) {
                break;
            }
            if (d.offset() < from) {
                continue; // the broker re-sent something already delivered
            }
            events.add(event(queue, d));
            next = Math.max(next, d.offset() + 1);
        }
        return new Result(events, events.isEmpty() ? null : "o:" + next);
    }

    private static Map<String, Object> event(String queue, Amqp.Delivery d) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("queue", queue);
        e.put("offset", d.offset());
        e.put("size", (long) d.body().length);
        String text = null;
        try {
            text = StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).onUnmappableCharacter(CodingErrorAction.REPORT)
                    .decode(ByteBuffer.wrap(d.body())).toString();
        } catch (CharacterCodingException notText) {
            // binary payload: reported, not guessed at
        }
        e.put("binary", text == null);
        boolean cut = text != null && text.length() > Amqp.MAX_EVENT_BODY;
        e.put("body", text == null ? "" : cut ? text.substring(0, Amqp.MAX_EVENT_BODY) : text);
        e.put("bodyTruncated", cut);
        e.put("messageId", d.messageId());
        e.put("contentType", d.contentType());
        return e;
    }

    static long parse(String cursor) throws ConnectorException {
        if (cursor == null || cursor.isBlank()) {
            return -1;
        }
        if (!cursor.startsWith("o:")) {
            throw new ConnectorException("the saved position is not valid: reset the trigger", false);
        }
        try {
            long v = Long.parseLong(cursor.substring(2));
            if (v < 0) {
                throw new NumberFormatException();
            }
            return v;
        } catch (NumberFormatException e) {
            throw new ConnectorException("the saved position is not valid: reset the trigger", false);
        }
    }
}
