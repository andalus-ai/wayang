package tech.kayys.wayang.endpoints.amqp;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.concurrent.BlockingQueue;
import java.util.concurrent.LinkedBlockingQueue;
import java.util.concurrent.TimeUnit;

import com.rabbitmq.client.Channel;
import com.rabbitmq.client.Connection;
import com.rabbitmq.client.ConnectionFactory;
import com.rabbitmq.client.Delivery;

/**
 * The production {@link Amqp.Reader}: consumes a RabbitMQ <em>stream</em> queue from an explicit offset ({@code x-stream-offset}). A stream keeps its
 * messages, so the position is Wayang's own (the cursor), exactly like the Kafka trigger; the acknowledgement a stream consumer must send moves
 * nothing that Wayang relies on. A classic queue cannot be read this way and is refused by the broker.
 */
final class RabbitReader implements Amqp.Reader {

    @Override
    public List<Amqp.Delivery> read(Amqp.Target t, String queue, long fromOffset, int max, int waitMs) throws Exception {
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
        try (Connection c = cf.newConnection("wayang-trigger"); Channel ch = c.createChannel()) {
            ch.basicQos(max);
            BlockingQueue<Delivery> got = new LinkedBlockingQueue<>();
            Map<String, Object> args = new HashMap<>();
            args.put("x-stream-offset", fromOffset < 0 ? "first" : (Object) fromOffset);
            String tag = ch.basicConsume(queue, false, args, (consumerTag, d) -> got.add(d), consumerTag -> { });
            List<Amqp.Delivery> out = new ArrayList<>();
            long deadline = System.nanoTime() + TimeUnit.MILLISECONDS.toNanos(waitMs);
            long lastTag = -1;
            while (out.size() < max) {
                Delivery d = got.poll(Math.max(1, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime())), TimeUnit.MILLISECONDS);
                if (d == null) {
                    break;
                }
                lastTag = d.getEnvelope().getDeliveryTag();
                Object off = d.getProperties().getHeaders() == null ? null : d.getProperties().getHeaders().get("x-stream-offset");
                if (!(off instanceof Number n)) {
                    throw new IllegalStateException("the queue is not a stream (no x-stream-offset on the message)");
                }
                out.add(new Amqp.Delivery(n.longValue(), d.getBody(), d.getProperties().getContentType(), d.getProperties().getMessageId()));
                if (System.nanoTime() >= deadline) {
                    break;
                }
            }
            if (lastTag >= 0) {
                ch.basicAck(lastTag, true);
            }
            ch.basicCancel(tag);
            return out;
        }
    }
}
