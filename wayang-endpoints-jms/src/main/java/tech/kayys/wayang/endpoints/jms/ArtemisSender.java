package tech.kayys.wayang.endpoints.jms;

import org.apache.activemq.artemis.jms.client.ActiveMQJMSConnectionFactory;

import jakarta.jms.Connection;
import jakarta.jms.DeliveryMode;
import jakarta.jms.Destination;
import jakarta.jms.MessageProducer;
import jakarta.jms.Session;
import jakarta.jms.TextMessage;

/**
 * The production {@link Jms.Sender}: one connection per message, persistent delivery (the Artemis client waits for the broker on a persistent send),
 * TLS with certificate and host-name verification. The broker URL is built from the validated host and port only.
 */
final class ArtemisSender implements Jms.Sender {

    @Override
    public void send(Jms.Target t, Jms.Message m) throws Exception {
        String url = "tcp://" + t.host() + ":" + t.port() + (t.tls() ? "?sslEnabled=true;verifyHost=true" : "");
        ActiveMQJMSConnectionFactory cf = new ActiveMQJMSConnectionFactory(url);
        cf.setConnectionTTL(30_000);
        cf.setCallTimeout(10_000);
        cf.setReconnectAttempts(0);
        try (Connection c = cf.createConnection(t.user(), t.password())) {
            try (Session s = c.createSession(false, Session.AUTO_ACKNOWLEDGE)) {
                Destination d = m.topic() ? s.createTopic(m.destination()) : s.createQueue(m.destination());
                TextMessage msg = s.createTextMessage(m.text());
                msg.setStringProperty("messageId", m.messageId());
                if (m.correlationId() != null) {
                    msg.setJMSCorrelationID(m.correlationId());
                }
                for (var e : m.properties().entrySet()) {
                    msg.setStringProperty(e.getKey(), e.getValue());
                }
                MessageProducer p = s.createProducer(d);
                p.setDeliveryMode(DeliveryMode.PERSISTENT);
                p.send(msg);
            }
        }
    }
}
