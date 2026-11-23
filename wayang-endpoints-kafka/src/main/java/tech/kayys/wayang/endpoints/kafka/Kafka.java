package tech.kayys.wayang.endpoints.kafka;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Properties;
import java.util.Set;
import java.util.regex.Pattern;

import org.apache.kafka.clients.consumer.Consumer;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.clients.producer.KafkaProducer;
import org.apache.kafka.clients.producer.Producer;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Shared rules for the Kafka endpoints. Off until the operator names the permitted brokers ({@code WAYANG_ENDPOINTS_KAFKA_SERVERS},
 * comma-separated {@code host:port}) and the permitted topic prefixes ({@code WAYANG_ENDPOINTS_KAFKA_TOPIC_PREFIXES}); a node can then
 * only reach what the operator listed. Authentication is {@code securityProtocol} (PLAINTEXT, SSL, SASL_PLAINTEXT, SASL_SSL),
 * {@code saslMechanism} (PLAIN, SCRAM-SHA-256, SCRAM-SHA-512), {@code saslUsername} and {@code saslPassword} (use {@code ${secret:NAME}});
 * the password is never echoed in an error. Anything else is not configurable: a node cannot set arbitrary client properties.
 */
final class Kafka {

    static final String SERVERS_ENV = "WAYANG_ENDPOINTS_KAFKA_SERVERS";
    static final String PREFIXES_ENV = "WAYANG_ENDPOINTS_KAFKA_TOPIC_PREFIXES";
    static final int MAX_VALUE_BYTES = 1 << 20;
    static final int MAX_RECORDS = 100;
    private static final Pattern TOPIC = Pattern.compile("[A-Za-z0-9._-]{1,249}");
    private static final Pattern HOSTPORT = Pattern.compile("[A-Za-z0-9._-]{1,253}:[0-9]{1,5}");
    private static final Set<String> PROTOCOLS = Set.of("PLAINTEXT", "SSL", "SASL_PLAINTEXT", "SASL_SSL");
    private static final Set<String> MECHANISMS = Set.of("PLAIN", "SCRAM-SHA-256", "SCRAM-SHA-512");

    /** Creates the clients; replaced in tests. */
    interface Clients {
        Producer<String, String> producer(Properties p);

        Consumer<String, String> consumer(Properties p);
    }

    static final Clients REAL = new Clients() {
        @Override
        public Producer<String, String> producer(Properties p) {
            return new KafkaProducer<>(p);
        }

        @Override
        public Consumer<String, String> consumer(Properties p) {
            return new KafkaConsumer<>(p);
        }
    };

    private final Set<String> servers;
    private final List<String> prefixes;
    final Clients clients;

    Kafka(Set<String> servers, List<String> prefixes, Clients clients) {
        this.servers = servers;
        this.prefixes = prefixes;
        this.clients = clients;
    }

    static Kafka fromEnvironment() {
        return new Kafka(Set.copyOf(list(System.getenv(SERVERS_ENV))), list(System.getenv(PREFIXES_ENV)), REAL);
    }

    private static List<String> list(String v) {
        return v == null ? List.of() : Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    boolean enabled() {
        return !servers.isEmpty() && !prefixes.isEmpty();
    }

    /** @return the validated topic */
    String check(Map<String, Object> config) throws ConnectorException {
        if (!enabled()) {
            throw new ConnectorException("kafka endpoints are not enabled (the operator sets " + SERVERS_ENV + " and " + PREFIXES_ENV + ")", false);
        }
        for (String s : servers(config)) {
            if (!servers.contains(s)) {
                throw new ConnectorException("broker '" + s + "' is not permitted by the operator", false);
            }
        }
        if (!(config.get("topic") instanceof String t) || !TOPIC.matcher(t).matches() || prefixes.stream().noneMatch(t::startsWith)) {
            throw new ConnectorException("topic must be a plain topic name starting with a permitted prefix", false);
        }
        return t;
    }

    static List<String> servers(Map<String, Object> config) throws ConnectorException {
        if (!(config.get("bootstrapServers") instanceof String b) || b.isBlank()) {
            throw new ConnectorException("bootstrapServers is needed (host:port, comma-separated)", false);
        }
        List<String> out = list(b);
        if (out.isEmpty() || out.size() > 10 || out.stream().anyMatch(s -> !HOSTPORT.matcher(s).matches())) {
            throw new ConnectorException("bootstrapServers must be 1-10 entries of host:port", false);
        }
        return out;
    }

    /** Common client properties: brokers, security, bounded timeouts; the password goes only into the client. */
    Properties base(Map<String, Object> config) throws ConnectorException {
        Properties p = new Properties();
        p.put("bootstrap.servers", String.join(",", servers(config)));
        p.put("client.id", "wayang");
        p.put("request.timeout.ms", "10000");
        p.put("default.api.timeout.ms", "10000");
        String protocol = String.valueOf(config.getOrDefault("securityProtocol", "PLAINTEXT")).toUpperCase(java.util.Locale.ROOT);
        if (!PROTOCOLS.contains(protocol)) {
            throw new ConnectorException("securityProtocol must be one of " + PROTOCOLS.stream().sorted().toList(), false);
        }
        p.put("security.protocol", protocol);
        if (protocol.startsWith("SASL")) {
            String mech = String.valueOf(config.getOrDefault("saslMechanism", "PLAIN")).toUpperCase(java.util.Locale.ROOT);
            if (!MECHANISMS.contains(mech)) {
                throw new ConnectorException("saslMechanism must be one of " + MECHANISMS.stream().sorted().toList(), false);
            }
            String user = config.get("saslUsername") instanceof String u ? u : null;
            String pass = config.get("saslPassword") instanceof String w ? w : null;
            if (user == null || pass == null || user.isEmpty() || pass.isEmpty()) {
                throw new ConnectorException("saslUsername and saslPassword are needed for a SASL protocol", false);
            }
            if (user.matches(".*[\"\\\\\\r\\n;].*") || pass.matches(".*[\"\\\\\\r\\n].*")) {
                throw new ConnectorException("saslUsername/saslPassword contain characters that cannot be used here", false);
            }
            String module = mech.equals("PLAIN") ? "org.apache.kafka.common.security.plain.PlainLoginModule" : "org.apache.kafka.common.security.scram.ScramLoginModule";
            p.put("sasl.mechanism", mech);
            p.put("sasl.jaas.config", module + " required username=\"" + user + "\" password=\"" + pass + "\";");
        }
        return p;
    }

    /** A failure without broker addresses beyond what the author configured and never with credentials. */
    static ConnectorException fail(String what, Throwable e, boolean retryable) {
        return new ConnectorException(what + " (" + e.getClass().getSimpleName() + ")", retryable);
    }
}
