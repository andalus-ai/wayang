package tech.kayys.wayang.endpoints.amqp;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/** The AMQP publish endpoint against a fake publisher (no broker): what is sent, and what is refused before anything is. */
class AmqpTest {

    static final class Fake implements Amqp.Publisher {
        final List<Amqp.Target> targets = new ArrayList<>();
        final List<Amqp.Message> sent = new ArrayList<>();
        Exception failure;

        @Override
        public void publish(Amqp.Target t, Amqp.Message m) throws Exception {
            if (failure != null) {
                throw failure;
            }
            targets.add(t);
            sent.add(m);
        }
    }

    final Fake fake = new Fake();
    final AmqpPublish connector = new AmqpPublish(new Amqp(Set.of("mq.example.com:5671", "mq.example.com:5672"), List.of("wy."), fake));

    static Map<String, Object> config() {
        Map<String, Object> c = new LinkedHashMap<>();
        c.put("host", "mq.example.com");
        c.put("user", "wayang");
        c.put("password", "s3cret-pw");
        c.put("exchange", "wy.orders");
        return c;
    }

    static ConnectorException refused(AmqpPublish c, Map<String, Object> cfg, Map<String, Object> in) {
        return assertThrows(ConnectorException.class, () -> c.call(cfg, in));
    }

    @Test
    void publishesAPersistentMessageWithTlsByDefault() throws Exception {
        var out = connector.call(config(), Map.of("body", "hello", "routingKey", "order.new", "messageId", "m-1", "headers", Map.of("k", "v")));
        assertEquals("wy.orders", out.get("exchange"));
        assertEquals("m-1", out.get("messageId"));
        assertEquals(5L, out.get("size"));
        assertEquals(5671, fake.targets.get(0).port());
        assertTrue(fake.targets.get(0).tls());
        var m = fake.sent.get(0);
        assertEquals("order.new", m.routingKey());
        assertEquals("hello", new String(m.body(), StandardCharsets.UTF_8));
        assertEquals("text/plain", m.contentType());
        assertEquals(Map.of("k", "v"), m.headers());
    }

    @Test
    void anObjectBodyIsSentAsJson() throws Exception {
        connector.call(config(), Map.of("body", Map.of("id", 7)));
        var m = fake.sent.get(0);
        assertEquals("application/json", m.contentType());
        assertTrue(new String(m.body(), StandardCharsets.UTF_8).contains("\"id\""));
        assertFalse(m.messageId().isBlank(), "a default id lets consumers de-duplicate");
    }

    @Test
    void withoutTlsThePlainPortIsUsedAndStillMustBePermitted() throws Exception {
        var c = config();
        c.put("tls", false);
        connector.call(c, Map.of("body", "x"));
        assertEquals(5672, fake.targets.get(0).port());
        c.put("port", 5673);
        assertTrue(refused(connector, c, Map.of("body", "x")).getMessage().contains("not permitted"));
    }

    @Test
    void nothingHappensUntilTheOperatorEnablesIt() {
        var off = new AmqpPublish(new Amqp(Set.of(), List.of(), fake));
        assertTrue(refused(off, config(), Map.of("body", "x")).getMessage().contains("not enabled"));
        assertTrue(fake.sent.isEmpty());
    }

    @Test
    void unpermittedBrokersExchangesAndTheDefaultExchangeAreRefused() {
        var c = config();
        c.put("host", "evil.example.com");
        assertTrue(refused(connector, c, Map.of("body", "x")).getMessage().contains("not permitted"));
        for (String ex : new String[] {"", "other.orders", "amq.direct", "wy.a b"}) {
            var d = config();
            d.put("exchange", ex);
            refused(connector, d, Map.of("body", "x"));
        }
        var e = config();
        e.remove("exchange");
        refused(connector, e, Map.of("body", "x"));
        assertTrue(fake.sent.isEmpty());
    }

    @Test
    void badInputsAreRefusedBeforeConnecting() {
        refused(connector, config(), Map.of());
        refused(connector, config(), Map.of("body", "x", "routingKey", "a/b"));
        refused(connector, config(), Map.of("body", "x".repeat(Amqp.MAX_BODY_BYTES + 1)));
        var many = new LinkedHashMap<String, Object>();
        for (int i = 0; i < 17; i++) {
            many.put("h" + i, "v");
        }
        refused(connector, config(), Map.of("body", "x", "headers", many));
        var noPw = config();
        noPw.remove("password");
        refused(connector, noPw, Map.of("body", "x"));
        var vh = config();
        vh.put("vhost", "a b");
        refused(connector, vh, Map.of("body", "x"));
        assertTrue(fake.sent.isEmpty());
    }

    @Test
    void anUnroutableMessageIsAnErrorAndATimeoutIsRetryable() {
        fake.failure = new Amqp.Unroutable();
        var lost = refused(connector, config(), Map.of("body", "x"));
        assertFalse(lost.retryable());
        fake.failure = new SocketTimeoutException("slow");
        assertTrue(refused(connector, config(), Map.of("body", "x")).retryable());
        fake.failure = new IllegalStateException("boom");
        assertFalse(refused(connector, config(), Map.of("body", "x")).retryable());
    }

    @Test
    void credentialsNeverAppearInErrorsOrTheTargetText() {
        fake.failure = new IllegalStateException("login failed for s3cret-pw");
        var e = refused(connector, config(), Map.of("body", "x"));
        assertFalse(e.getMessage().contains("s3cret-pw"));
        assertFalse(new Amqp.Target("h", 1, true, "/", "wayang", "s3cret-pw").toString().contains("s3cret-pw"));
    }

    @Test
    void theExtensionDescribesItsComponent() {
        var ext = new AmqpExtension(new Amqp(Set.of(), List.of(), fake));
        assertEquals(Set.of("amqp-publish"), ext.connectors().keySet());
        assertEquals(1, ext.components().size());
        assertEquals("wayang.endpoints.amqp-publish", ext.components().get(0).id());
    }
}
