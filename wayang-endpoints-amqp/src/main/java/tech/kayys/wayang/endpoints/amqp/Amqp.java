package tech.kayys.wayang.endpoints.amqp;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Shared rules of the AMQP 0-9-1 (RabbitMQ) endpoint. Off until the operator lists the permitted brokers
 * ({@code WAYANG_ENDPOINTS_AMQP_HOSTS}, comma-separated {@code host:port}; port 5671 with TLS, 5672 without when a node omits it) and the
 * permitted exchange prefixes ({@code WAYANG_ENDPOINTS_AMQP_EXCHANGE_PREFIXES}). The default exchange is refused: it would let a node publish
 * straight into any queue by name. TLS is on unless the node sets {@code tls: false}, and then the broker's certificate and host name are verified.
 * Credentials ({@code user}, {@code password}, use {@code ${secret:NAME}}) are never echoed; failures carry the exception type only.
 */
final class Amqp {

    static final String HOSTS_ENV = "WAYANG_ENDPOINTS_AMQP_HOSTS";
    static final String PREFIXES_ENV = "WAYANG_ENDPOINTS_AMQP_EXCHANGE_PREFIXES";
    static final String QUEUE_PREFIXES_ENV = "WAYANG_ENDPOINTS_AMQP_STREAM_PREFIXES";
    static final int MAX_EVENT_BODY = 64 * 1024;
    static final int MAX_BODY_BYTES = 1 << 20;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]{1,253}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,255}");
    private static final Pattern ROUTING = Pattern.compile("[A-Za-z0-9._*#:-]{0,255}");
    private static final Pattern VHOST = Pattern.compile("[A-Za-z0-9._/-]{1,255}");

    record Target(String host, int port, boolean tls, String vhost, String user, String password) {
        @Override
        public String toString() {
            return "Target[" + host + ":" + port + "]";
        }
    }

    record Message(String exchange, String routingKey, byte[] body, String contentType, String messageId, Map<String, Object> headers) {
    }

    /** Publishes one message and waits for the broker's confirmation; implemented over the client library, faked in tests. */
    interface Publisher {
        void publish(Target target, Message message) throws Exception;
    }

    record Delivery(long offset, byte[] body, String contentType, String messageId) {
    }

    /** Reads up to {@code max} messages of a stream queue starting at {@code fromOffset} (-1 = the first available) and waits at most {@code waitMs}. */
    interface Reader {
        List<Delivery> read(Target target, String queue, long fromOffset, int max, int waitMs) throws Exception;
    }

    private final Set<String> hosts;
    private final List<String> prefixes;
    private final List<String> streamPrefixes;
    final Publisher publisher;
    final Reader reader;

    Amqp(Set<String> hosts, List<String> prefixes, Publisher publisher) {
        this(hosts, prefixes, List.of(), publisher, null);
    }

    Amqp(Set<String> hosts, List<String> prefixes, List<String> streamPrefixes, Publisher publisher, Reader reader) {
        this.hosts = hosts;
        this.prefixes = prefixes;
        this.streamPrefixes = streamPrefixes;
        this.publisher = publisher;
        this.reader = reader;
    }

    static Amqp fromEnvironment() {
        return new Amqp(Set.copyOf(list(System.getenv(HOSTS_ENV))), list(System.getenv(PREFIXES_ENV)), list(System.getenv(QUEUE_PREFIXES_ENV)),
                new RabbitPublisher(), new RabbitReader());
    }

    private static List<String> list(String v) {
        return v == null ? List.of() : Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    Target target(Map<String, Object> config) throws ConnectorException {
        if (hosts.isEmpty() || prefixes.isEmpty()) {
            throw new ConnectorException("amqp endpoints are not enabled (the operator sets " + HOSTS_ENV + " and " + PREFIXES_ENV + ")", false);
        }
        return resolve(config);
    }

    /** Like {@link #target} for the trigger, which needs the stream-queue prefixes instead of exchange prefixes. */
    Target streamTarget(Map<String, Object> config) throws ConnectorException {
        if (hosts.isEmpty() || streamPrefixes.isEmpty() || reader == null) {
            throw new ConnectorException("amqp triggers are not enabled (the operator sets " + HOSTS_ENV + " and " + QUEUE_PREFIXES_ENV + ")", false);
        }
        return resolve(config);
    }

    String streamQueue(Map<String, Object> config) throws ConnectorException {
        if (!(config.get("queue") instanceof String q) || !NAME.matcher(q).matches() || streamPrefixes.stream().noneMatch(q::startsWith)) {
            throw new ConnectorException("queue must be a plain name starting with a permitted stream prefix", false);
        }
        return q;
    }

    private Target resolve(Map<String, Object> config) throws ConnectorException {
        if (!(config.get("host") instanceof String host) || !HOST.matcher(host).matches()) {
            throw new ConnectorException("host is needed (a plain host name or address)", false);
        }
        boolean tls = config.get("tls") == null || Boolean.parseBoolean(String.valueOf(config.get("tls")));
        int port = tls ? 5671 : 5672;
        if (config.get("port") != null) {
            try {
                port = Integer.parseInt(String.valueOf(config.get("port")).trim());
            } catch (NumberFormatException e) {
                throw new ConnectorException("port must be a number", false);
            }
            if (port < 1 || port > 65535) {
                throw new ConnectorException("port must be 1-65535", false);
            }
        }
        if (!hosts.contains(host + ":" + port)) {
            throw new ConnectorException("broker " + host + ":" + port + " is not permitted by the operator", false);
        }
        String vhost = config.get("vhost") == null ? "/" : String.valueOf(config.get("vhost"));
        if (!VHOST.matcher(vhost).matches()) {
            throw new ConnectorException("vhost is not valid", false);
        }
        if (!(config.get("user") instanceof String user) || user.isBlank() || !(config.get("password") instanceof String pw) || pw.isEmpty()) {
            throw new ConnectorException("user and password are needed", false);
        }
        return new Target(host, port, tls, vhost, user, pw);
    }

    String exchange(Map<String, Object> config) throws ConnectorException {
        if (!(config.get("exchange") instanceof String ex) || !NAME.matcher(ex).matches() || prefixes.stream().noneMatch(ex::startsWith)) {
            throw new ConnectorException("exchange must be a plain name starting with a permitted prefix (the default exchange is not allowed)", false);
        }
        return ex;
    }

    static String routingKey(Object v) throws ConnectorException {
        String k = v == null ? "" : String.valueOf(v);
        if (!ROUTING.matcher(k).matches()) {
            throw new ConnectorException("routingKey may contain letters, digits and . _ * # : - only (at most 255)", false);
        }
        return k;
    }

    static ConnectorException fail(String what, Throwable e, boolean retryable) {
        return new ConnectorException(what + " (" + e.getClass().getSimpleName() + ")", retryable);
    }

    static boolean transientFailure(Throwable e) {
        String n = e.getClass().getSimpleName();
        return e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                || e instanceof java.io.EOFException || n.contains("Timeout") || n.contains("Shutdown") || n.contains("Unreachable");
    }

    /** Thrown by a publisher when the broker could not route the message to any queue. */
    static final class Unroutable extends Exception {
        private static final long serialVersionUID = 1L;

        Unroutable() {
            super("unroutable");
        }
    }
}
