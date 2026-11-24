package tech.kayys.wayang.endpoints.kafka;

import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.TreeMap;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.common.KafkaException;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.apache.kafka.common.header.Header;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Trigger source {@code kafka}: a run per new record. Config: {@code bootstrapServers}, {@code topic} (see {@link Kafka}), {@code startFrom}
 * ({@code earliest} or {@code latest}; needed, applied the first time and to partitions that appear later) and {@code maxRecords} (1-100, default
 * 50). Wayang keeps the position, not a consumer group: the cursor is the next offset per partition ({@code 0:120,1:98}), so the trigger is
 * at-least-once and resumes exactly where it stopped, with no group coordination and no commits on the broker. If the broker has deleted data the
 * cursor points at (retention), the poll fails with a clear message instead of silently skipping: an operator resets the trigger. Event fields:
 * {@code topic}, {@code partition}, {@code offset}, {@code key}, {@code value}, {@code timestamp}, {@code headers} (text values).
 */
final class KafkaPoller implements Poller {

    private final Kafka kafka;

    KafkaPoller(Kafka kafka) {
        this.kafka = kafka;
    }

    @Override
    public Result poll(Map<String, Object> config, String cursor) throws Exception {
        String topic = kafka.check(config);
        boolean earliest = switch (String.valueOf(config.get("startFrom"))) {
            case "earliest" -> true;
            case "latest" -> false;
            default -> throw new ConnectorException("startFrom is needed: earliest or latest", false);
        };
        int max = 50;
        if (config.get("maxRecords") != null) {
            try {
                max = Integer.parseInt(String.valueOf(config.get("maxRecords")).trim());
            } catch (NumberFormatException e) {
                throw new ConnectorException("maxRecords must be a number from 1 to " + Kafka.MAX_RECORDS, false);
            }
            if (max < 1 || max > Kafka.MAX_RECORDS) {
                throw new ConnectorException("maxRecords must be a number from 1 to " + Kafka.MAX_RECORDS, false);
            }
        }
        Map<Integer, Long> positions = parse(cursor);
        Properties p = kafka.base(config);
        p.put("key.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("value.deserializer", "org.apache.kafka.common.serialization.StringDeserializer");
        p.put("enable.auto.commit", "false");
        p.put("auto.offset.reset", "none");
        p.put("max.poll.records", String.valueOf(max));
        p.put("fetch.max.bytes", String.valueOf(Kafka.MAX_VALUE_BYTES * 4));
        p.put("max.partition.fetch.bytes", String.valueOf(Kafka.MAX_VALUE_BYTES));
        try (Consumer<String, String> consumer = kafka.clients.consumer(p)) {
            List<PartitionInfo> infos = consumer.partitionsFor(topic, Duration.ofSeconds(10));
            if (infos == null || infos.isEmpty()) {
                throw new ConnectorException("the topic has no partitions (or does not exist)", false);
            }
            List<TopicPartition> all = new ArrayList<>();
            infos.forEach(i -> all.add(new TopicPartition(topic, i.partition())));
            consumer.assign(all);
            List<TopicPartition> fresh = new ArrayList<>();
            for (TopicPartition tp : all) {
                Long at = positions.get(tp.partition());
                if (at != null) {
                    consumer.seek(tp, at);
                } else {
                    fresh.add(tp);
                }
            }
            if (!fresh.isEmpty()) {
                if (earliest) {
                    consumer.seekToBeginning(fresh);
                } else {
                    consumer.seekToEnd(fresh);
                }
            }
            ConsumerRecords<String, String> got = consumer.poll(Duration.ofSeconds(2));
            List<Map<String, Object>> events = new ArrayList<>();
            Map<Integer, Long> next = new TreeMap<>(positions);
            for (ConsumerRecord<String, String> r : got) {
                if (events.size() >= max) {
                    break;
                }
                events.add(event(r));
                next.merge(r.partition(), r.offset() + 1, Math::max);
            }
            for (TopicPartition tp : fresh) {
                // pin the starting point even when nothing arrived, so "latest" does not move between polls
                next.putIfAbsent(tp.partition(), consumer.position(tp, Duration.ofSeconds(10)));
            }
            for (TopicPartition tp : all) {
                next.putIfAbsent(tp.partition(), consumer.position(tp, Duration.ofSeconds(10)));
            }
            String encoded = format(next);
            return new Result(events, events.isEmpty() && encoded.equals(cursor) ? null : encoded);
        } catch (ConnectorException e) {
            throw e;
        } catch (KafkaException e) {
            String name = e.getClass().getSimpleName();
            if (name.contains("OffsetOutOfRange") || name.contains("NoOffsetForPartition")) {
                throw new ConnectorException("the saved position is no longer available on the broker (retention?): reset the trigger to start again", false);
            }
            throw Kafka.fail("polling the topic failed", e, e instanceof org.apache.kafka.common.errors.RetriableException);
        }
    }

    private static Map<String, Object> event(ConsumerRecord<String, String> r) {
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("topic", r.topic());
        e.put("partition", r.partition());
        e.put("offset", r.offset());
        e.put("key", r.key());
        e.put("value", r.value());
        e.put("timestamp", r.timestamp());
        Map<String, Object> h = new LinkedHashMap<>();
        for (Header header : r.headers()) {
            h.put(header.key(), header.value() == null ? null : new String(header.value(), java.nio.charset.StandardCharsets.UTF_8));
        }
        e.put("headers", h);
        return e;
    }

    static Map<Integer, Long> parse(String cursor) throws ConnectorException {
        Map<Integer, Long> m = new TreeMap<>();
        if (cursor == null || cursor.isBlank()) {
            return m;
        }
        try {
            for (String part : cursor.split(",")) {
                String[] kv = part.split(":");
                if (kv.length != 2) {
                    throw new NumberFormatException();
                }
                long off = Long.parseLong(kv[1]);
                if (off < 0) {
                    throw new NumberFormatException();
                }
                m.put(Integer.parseInt(kv[0]), off);
            }
        } catch (NumberFormatException e) {
            throw new ConnectorException("the saved kafka position is not valid", false);
        }
        return m;
    }

    static String format(Map<Integer, Long> m) {
        StringBuilder sb = new StringBuilder();
        new TreeMap<>(m).forEach((k, v) -> sb.append(sb.length() == 0 ? "" : ",").append(k).append(':').append(v));
        return sb.toString();
    }
}
