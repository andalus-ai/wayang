package tech.kayys.wayang.endpoints.jms;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/** The JMS send endpoint against a fake sender (no broker): what is sent, and what is refused before anything is. */
class JmsTest {

    static final class Fake implements Jms.Sender {
        final List<Jms.Target> targets = new ArrayList<>();
        final List<Jms.Message> sent = new ArrayList<>();
        Exception failure;

        @Override
        public void send(Jms.Target t, Jms.Message m) throws Exception {
            if (failure != null) {
                throw failure;
            }
            targets.add(t);
            sent.add(m);
        }
    }

    final Fake fake = new Fake();
    final JmsSend connector = new JmsSend(new Jms(Set.of("mq.example.com:61616", "mq.example.com:61617"), List.of("wy."), fake));

    static Map<String, Object> config() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("host", "mq.example.com");
        c.put("user", "wayang");
        c.put("password", "s3cret-pw");
        c.put("destination", "wy.orders");
        return c;
    }

    static ConnectorException refused(JmsSend c, Map<String, Object> cfg, Map<String, Object> in) {
        return assertThrows(ConnectorException.class, () -> c.call(cfg, in));
    }

    @Test
    void sendsToAQueueWithTlsByDefault() throws Exception {
        var out = connector.call(config(), Map.of("body", "hello", "messageId", "m-1", "correlationId", "c-1", "properties", Map.of("kind", "order")));
        assertEquals("wy.orders", out.get("destination"));
        assertEquals("m-1", out.get("messageId"));
        assertEquals(5L, out.get("size"));
        assertTrue(fake.targets.get(0).tls());
        var m = fake.sent.get(0);
        assertFalse(m.topic());
        assertEquals("c-1", m.correlationId());
        assertEquals(Map.of("kind", "order"), m.properties());
    }

    @Test
    void anObjectBodyIsSentAsJsonAndATopicOnRequest() throws Exception {
        var cfg = config();
        cfg.put("destinationType", "topic");
        connector.call(cfg, Map.of("body", Map.of("a", 1)));
        assertEquals("{\"a\":1}", fake.sent.get(0).text());
        assertTrue(fake.sent.get(0).topic());
        assertEquals(36, fake.sent.get(0).messageId().length(), "a random id by default");
    }

    @Test
    void refusesWhatTheOperatorDidNotPermit() {
        var cfg = config();
        cfg.put("host", "evil.example.com");
        assertTrue(refused(connector, cfg, Map.of("body", "x")).getMessage().contains("not permitted"));
        cfg = config();
        cfg.put("port", 9999);
        assertTrue(refused(connector, cfg, Map.of("body", "x")).getMessage().contains("not permitted"));
        cfg = config();
        cfg.put("destination", "other.orders");
        assertTrue(refused(connector, cfg, Map.of("body", "x")).getMessage().contains("permitted prefix"));
        cfg = config();
        cfg.put("destination", "wy.a b");
        refused(connector, cfg, Map.of("body", "x"));
        cfg = config();
        cfg.put("host", "mq.example.com/x?y");
        refused(connector, cfg, Map.of("body", "x"));
        assertTrue(fake.sent.isEmpty());
    }

    @Test
    void isOffUntilTheOperatorConfiguresIt() {
        var off = new JmsSend(new Jms(Set.of(), List.of(), fake));
        assertTrue(refused(off, config(), Map.of("body", "x")).getMessage().contains("not enabled"));
    }

    @Test
    void refusesBadInputsBeforeSending() {
        refused(connector, config(), Map.of());
        refused(connector, config(), Map.of("body", "x".repeat(Jms.MAX_BODY_BYTES + 1)));
        refused(connector, config(), Map.of("body", "x", "properties", Map.of("bad name", "v")));
        refused(connector, config(), Map.of("body", "x", "properties", Map.of("messageId", "v")));
        refused(connector, config(), Map.of("body", "x", "messageId", "m".repeat(256)));
        var cfg = config();
        cfg.put("destinationType", "both");
        refused(connector, cfg, Map.of("body", "x"));
        cfg = config();
        cfg.remove("password");
        refused(connector, cfg, Map.of("body", "x"));
        assertTrue(fake.sent.isEmpty());
    }

    @Test
    void failuresNeverEchoCredentialsAndTimeoutsAreRetryable() {
        fake.failure = new SocketTimeoutException("s3cret-pw timed out");
        var e = refused(connector, config(), Map.of("body", "x"));
        assertTrue(e.retryable());
        assertFalse(e.getMessage().contains("s3cret-pw"));
        fake.failure = new IllegalStateException("login failed for wayang / s3cret-pw");
        e = refused(connector, config(), Map.of("body", "x"));
        assertFalse(e.retryable());
        assertFalse(e.getMessage().contains("s3cret-pw"));
        fake.failure = new RuntimeException("wrapped", new java.net.ConnectException("down"));
        assertTrue(refused(connector, config(), Map.of("body", "x")).retryable());
    }

    @Test
    void theTargetNeverPrintsItsPassword() throws Exception {
        connector.call(config(), Map.of("body", "x"));
        assertFalse(fake.targets.get(0).toString().contains("s3cret-pw"));
    }
}
