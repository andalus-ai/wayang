package tech.kayys.wayang.endpoints.kafka;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.InvocationHandler;
import java.lang.reflect.Proxy;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Collection;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.Future;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.producer.Producer;
import org.apache.kafka.clients.producer.ProducerRecord;
import org.apache.kafka.clients.producer.RecordMetadata;
import org.apache.kafka.common.PartitionInfo;
import org.apache.kafka.common.TopicPartition;
import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/** The Kafka endpoints against fake clients (no broker): what is sent, what is read and where, and what is refused. */
class KafkaTest {

    /** What the fake clients saw and will answer. */
    static final class Fake implements Kafka.Clients {
        final List<ProducerRecord<String, String>> sent = new ArrayList<>();
        final List<Properties> props = new ArrayList<>();
        final Map<TopicPartition, Long> seeks = new LinkedHashMap<>();
        final List<String> positioning = new ArrayList<>();
        final Map<TopicPartition, List<ConsumerRecord<String, String>>> available = new LinkedHashMap<>();
        final Map<Integer, Long> end = new TreeMap<>();
        RuntimeException publishFailure;
        int partitions = 2;

        @Override
        public Producer<String, String> producer(Properties p) {
            props.add(p);
            @SuppressWarnings("unchecked")
            Producer<String, String> out = (Producer<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Producer.class},
                    (InvocationHandler) (proxy, m, a) -> {
                        if (m.getName().equals("send")) {
                            @SuppressWarnings("unchecked")
                            ProducerRecord<String, String> r = (ProducerRecord<String, String>) a[0];
                            if (publishFailure != null) {
                                CompletableFuture<RecordMetadata> f = new CompletableFuture<>();
                                f.completeExceptionally(publishFailure);
                                return f;
                            }
                            sent.add(r);
                            return CompletableFuture.completedFuture(new RecordMetadata(new TopicPartition(r.topic(), 1), 40L, 2L, 1700L, 3, 4));
                        }
                        return null; // close and the rest
                    });
            return out;
        }

        @Override
        public Consumer<String, String> consumer(Properties p) {
            props.add(p);
            @SuppressWarnings("unchecked")
            Consumer<String, String> out = (Consumer<String, String>) Proxy.newProxyInstance(getClass().getClassLoader(), new Class<?>[] {Consumer.class},
                    (InvocationHandler) (proxy, m, a) -> switch (m.getName()) {
                        case "partitionsFor" -> {
                            List<PartitionInfo> l = new ArrayList<>();
                            for (int i = 0; i < partitions; i++) {
                                l.add(new PartitionInfo((String) a[0], i, null, null, null));
                            }
                            yield l;
                        }
                        case "seek" -> {
                            seeks.put((TopicPartition) a[0], (Long) a[1]);
                            yield null;
                        }
                        case "seekToBeginning" -> {
                            for (Object tp : (Collection<?>) a[0]) {
                                seeks.put((TopicPartition) tp, 0L);
                                positioning.add("begin:" + ((TopicPartition) tp).partition());
                            }
                            yield null;
                        }
                        case "seekToEnd" -> {
                            for (Object tp : (Collection<?>) a[0]) {
                                seeks.put((TopicPartition) tp, end.getOrDefault(((TopicPartition) tp).partition(), 0L));
                                positioning.add("end:" + ((TopicPartition) tp).partition());
                            }
                            yield null;
                        }
                        case "position" -> seeks.getOrDefault((TopicPartition) a[0], 0L);
                        case "poll" -> {
                            Map<TopicPartition, List<ConsumerRecord<String, String>>> out2 = new LinkedHashMap<>();
                            available.forEach((tp, recs) -> {
                                long from = seeks.getOrDefault(tp, 0L);
                                List<ConsumerRecord<String, String>> keep = recs.stream().filter(r -> r.offset() >= from).toList();
                                if (!keep.isEmpty()) {
                                    out2.put(tp, keep);
                                }
                            });
                            yield records(out2);
                        }
                        default -> null; // assign, close
                    });
            return out;
        }

        void add(int partition, long offset, String key, String value) {
            available.computeIfAbsent(new TopicPartition("wy.orders", partition), k -> new ArrayList<>())
                    .add(new ConsumerRecord<>("wy.orders", partition, offset, key, value));
        }
    }

    /** ConsumerRecords has had different constructors across client versions. */
    @SuppressWarnings({"unchecked", "rawtypes"})
    static ConsumerRecords<String, String> records(Map<TopicPartition, List<ConsumerRecord<String, String>>> m) {
        try {
            try {
                return (ConsumerRecords<String, String>) ConsumerRecords.class.getConstructor(Map.class).newInstance(m);
            } catch (NoSuchMethodException e) {
                return (ConsumerRecords<String, String>) ConsumerRecords.class.getConstructor(Map.class, Map.class).newInstance(m, Map.of());
            }
        } catch (ReflectiveOperationException e) {
            throw new AssertionError(e);
        }
    }

    private static Kafka kafka(Fake f) {
        return new Kafka(Set.of("broker1:9092"), List.of("wy."), f);
    }

    private static Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new LinkedHashMap<>(Map.of("bootstrapServers", "broker1:9092", "topic", "wy.orders"));
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    @Test
    void publishesAWaitedAcknowledgedRecordWithKeyHeadersAndJson() throws Exception {
        Fake f = new Fake();
        var out = new KafkaPublish(kafka(f)).call(cfg(), Map.of("value", Map.of("id", 7), "key", "k1", "headers", Map.of("src", "wayang")));
        assertEquals("wy.orders", out.get("topic"));
        assertEquals(1, out.get("partition"));
        assertEquals(42L, out.get("offset"));
        assertEquals("{\"id\":7}", f.sent.get(0).value());
        assertEquals("k1", f.sent.get(0).key());
        assertEquals("wayang", new String(f.sent.get(0).headers().lastHeader("src").value(), StandardCharsets.UTF_8));
        assertEquals("all", f.props.get(0).get("acks"));
        assertEquals("true", f.props.get(0).get("enable.idempotence"));
        assertEquals("PLAINTEXT", f.props.get(0).get("security.protocol"));
        new KafkaPublish(kafka(f)).call(cfg(), Map.of("value", "text"));
        assertEquals("text", f.sent.get(1).value());
    }

    @Test
    void nothingIsReachableUntilTheOperatorPermitsIt() {
        Fake f = new Fake();
        var off = new Kafka(Set.of(), List.of(), f);
        assertThrows(ConnectorException.class, () -> new KafkaPublish(off).call(cfg(), Map.of("value", "x")));
        assertThrows(ConnectorException.class, () -> new KafkaPoller(off).poll(cfg("startFrom", "earliest"), null));
        var k = kafka(f);
        for (Map<String, Object> bad : List.of(cfg("bootstrapServers", "evil:9092"), cfg("bootstrapServers", "broker1:9092,evil:9092"),
                cfg("topic", "other.topic"), cfg("topic", "wy.a/b"), cfg("topic", "wy.."+ "x".repeat(300)), cfg("bootstrapServers", "bad host:1"),
                cfg("securityProtocol", "MAGIC"))) {
            assertThrows(ConnectorException.class, () -> new KafkaPublish(k).call(bad, Map.of("value", "x")), String.valueOf(bad));
        }
        assertTrue(f.sent.isEmpty(), "no client was ever asked to send");
    }

    @Test
    void saslCredentialsGoOnlyIntoTheClientAndNeverIntoErrors() throws Exception {
        Fake f = new Fake();
        new KafkaPublish(kafka(f)).call(cfg("securityProtocol", "SASL_SSL", "saslMechanism", "SCRAM-SHA-512", "saslUsername", "svc", "saslPassword", "s3cr3t-pw"),
                Map.of("value", "x"));
        String jaas = String.valueOf(f.props.get(0).get("sasl.jaas.config"));
        assertTrue(jaas.contains("ScramLoginModule") && jaas.contains("username=\"svc\"") && jaas.contains("password=\"s3cr3t-pw\""));
        for (String badPw : new String[] {"a\"b", "a\\b", "a\nb"}) {
            var e = assertThrows(ConnectorException.class, () -> new KafkaPublish(kafka(f)).call(
                    cfg("securityProtocol", "SASL_SSL", "saslUsername", "svc", "saslPassword", badPw), Map.of("value", "x")));
            assertFalse(e.getMessage().contains(badPw));
        }
        var missing = assertThrows(ConnectorException.class, () -> new KafkaPublish(kafka(f)).call(cfg("securityProtocol", "SASL_SSL", "saslUsername", "svc"), Map.of("value", "x")));
        assertFalse(missing.getMessage().contains("svc"));
        f.publishFailure = new IllegalStateException("broker said password s3cr3t-pw is wrong");
        var failed = assertThrows(ConnectorException.class, () -> new KafkaPublish(kafka(f)).call(
                cfg("securityProtocol", "SASL_SSL", "saslUsername", "svc", "saslPassword", "s3cr3t-pw"), Map.of("value", "x")));
        assertFalse(failed.getMessage().contains("s3cr3t-pw"), "a broker message is not repeated");
    }

    @Test
    void aBrokerTimeoutIsRetryableButBadInputIsNot() {
        Fake f = new Fake();
        f.publishFailure = new org.apache.kafka.common.errors.TimeoutException("slow");
        assertTrue(assertThrows(ConnectorException.class, () -> new KafkaPublish(kafka(f)).call(cfg(), Map.of("value", "x"))).retryable());
        f.publishFailure = new org.apache.kafka.common.errors.RecordTooLargeException("big");
        assertFalse(assertThrows(ConnectorException.class, () -> new KafkaPublish(kafka(f)).call(cfg(), Map.of("value", "x"))).retryable());
        assertFalse(assertThrows(ConnectorException.class, () -> new KafkaPublish(kafka(new Fake())).call(cfg(), Map.of())).retryable());
        assertThrows(ConnectorException.class, () -> new KafkaPublish(kafka(new Fake())).call(cfg(), Map.of("value", "x".repeat(Kafka.MAX_VALUE_BYTES + 1))));
    }

    @Test
    void theTriggerReadsFromTheStartThenResumesWhereItStopped() throws Exception {
        Fake f = new Fake();
        f.add(0, 0, "a", "v0");
        f.add(0, 1, "a", "v1");
        f.add(1, 0, "b", "w0");
        var poller = new KafkaPoller(kafka(f));
        var first = poller.poll(cfg("startFrom", "earliest", "maxRecords", 50), null);
        assertEquals(3, first.events().size());
        assertEquals("0:2,1:1", first.nextCursor());
        assertEquals("wy.orders", first.events().get(0).get("topic"));
        assertEquals("v0", first.events().get(0).get("value"));
        assertEquals("none", f.props.get(0).get("auto.offset.reset"));
        assertEquals("false", f.props.get(0).get("enable.auto.commit"));

        f.seeks.clear();
        f.add(0, 2, "a", "v2");
        var second = poller.poll(cfg("startFrom", "earliest"), first.nextCursor());
        assertEquals(1, second.events().size(), "only what is new");
        assertEquals("v2", second.events().get(0).get("value"));
        assertEquals(2L, f.seeks.get(new TopicPartition("wy.orders", 0)), "resumed at the saved offset");
        assertEquals("0:3,1:1", second.nextCursor());

        var none = poller.poll(cfg("startFrom", "earliest"), second.nextCursor());
        assertTrue(none.events().isEmpty());
        assertNull(none.nextCursor(), "nothing new: the cursor is unchanged");
    }

    @Test
    void latestPinsItsStartingPointSoNothingIsMissedBetweenPolls() throws Exception {
        Fake f = new Fake();
        f.end.put(0, 10L);
        f.end.put(1, 4L);
        var poller = new KafkaPoller(kafka(f));
        var first = poller.poll(cfg("startFrom", "latest"), null);
        assertTrue(first.events().isEmpty());
        assertEquals("0:10,1:4", first.nextCursor(), "the end is remembered even though no record arrived");
        f.seeks.clear();
        f.add(0, 10, "k", "late");
        var second = poller.poll(cfg("startFrom", "latest"), first.nextCursor());
        assertEquals("late", second.events().get(0).get("value"));
    }

    @Test
    void aBadConfigurationOrCursorIsRefusedClearly() {
        Fake f = new Fake();
        var poller = new KafkaPoller(kafka(f));
        assertThrows(ConnectorException.class, () -> poller.poll(cfg(), null), "startFrom is needed");
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("startFrom", "middle"), null));
        for (String bad : new String[] {"x", "0", "0:x", "a:1", "0:-1", "0:1,2"}) {
            assertThrows(ConnectorException.class, () -> poller.poll(cfg("startFrom", "earliest"), bad), bad);
        }
        for (Object bad : new Object[] {0, 101, "many"}) {
            assertThrows(ConnectorException.class, () -> poller.poll(cfg("startFrom", "earliest", "maxRecords", bad), null), String.valueOf(bad));
        }
        f.partitions = 0;
        assertThrows(ConnectorException.class, () -> poller.poll(cfg("startFrom", "earliest"), null));
    }

    @Test
    void theExtensionExposesBothEndpointsAndDescribesThem() {
        var e = new KafkaExtension(kafka(new Fake()));
        assertEquals(Set.of("kafka-publish"), e.connectors().keySet());
        assertEquals(Set.of("kafka"), e.pollers().keySet());
        assertEquals(1, e.components().size());
        assertEquals("wayang.endpoints.kafka-publish", e.components().get(0).id());
        assertEquals("1:3", KafkaPoller.format(Map.of(1, 3L)));
        assertEquals(Map.of(0, 5L, 1, 6L), KafkaPoller.parse("0:5,1:6"));
    }
}
