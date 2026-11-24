package tech.kayys.wayang.endpoints.amqp;

import java.util.concurrent.atomic.AtomicBoolean;

import com.rabbitmq.client.AMQP;
import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;

/** The production {@link Amqp.Publisher}: one connection per message, publisher confirms, mandatory routing, TLS with host name verification. */
final class RabbitPublisher implements Amqp.Publisher {

    @Override
    public void publish(Amqp.Target t, Amqp.Message m) throws Exception {
        ConnectionFactory cf = new ConnectionFactory();
        cf.setHost(t.host());
        cf.setPort(t.port());
        cf.setVirtualHost(t.vhost());
        cf.setUsername(t.user());
        cf.setPassword(t.password());
        cf.setConnectionTimeout(10_000);
        cf.setHandshakeTimeout(10_000);
        cf.setAutomaticRecoveryEnabled(false);
        if (t.tls()) {
            cf.useSslProtocol();
            cf.enableHostnameVerification();
        }
        AMQP.BasicProperties.Builder props = new AMQP.BasicProperties.Builder().deliveryMode(2);
        if (m.contentType() != null) {
            props.contentType(m.contentType());
        }
        if (m.messageId() != null) {
            props.messageId(m.messageId());
        }
        if (!m.headers().isEmpty()) {
            props.headers(m.headers());
        }
        try (Connection c = cf.newConnection("wayang"); Channel ch = c.createChannel()) {
            AtomicBoolean returned = new AtomicBoolean();
            ch.addReturnListener(r -> returned.set(true));
            ch.confirmSelect();
            ch.basicPublish(m.exchange(), m.routingKey(), true, props.build(), m.body());
            ch.waitForConfirmsOrDie(10_000); // a returned (unroutable) message is delivered to the listener before the confirm
            if (returned.get()) {
                throw new Amqp.Unroutable();
            }
        }
    }
}
