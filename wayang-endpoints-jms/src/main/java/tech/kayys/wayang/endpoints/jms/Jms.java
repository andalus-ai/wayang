package tech.kayys.wayang.endpoints.jms;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Shared rules of the JMS endpoint (sending only). Off until the operator lists the permitted brokers
 * ({@code WAYANG_ENDPOINTS_JMS_BROKERS}, comma-separated {@code host:port}) and the permitted destination prefixes
 * ({@code WAYANG_ENDPOINTS_JMS_DESTINATION_PREFIXES}). TLS is on unless the node sets {@code tls: false}, and then the certificate and host name are
 * verified. The broker address is built here from validated parts; a node cannot pass a connection URL. Credentials ({@code user}, {@code password},
 * use {@code ${secret:NAME}}) are never echoed; failures carry the exception type only.
 */
final class Jms {

    static final String BROKERS_ENV = "WAYANG_ENDPOINTS_JMS_BROKERS";
    static final String PREFIXES_ENV = "WAYANG_ENDPOINTS_JMS_DESTINATION_PREFIXES";
    static final int MAX_BODY_BYTES = 1 << 20;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]{1,253}");
    private static final Pattern NAME = Pattern.compile("[A-Za-z0-9._:-]{1,255}");
    private static final Pattern PROPERTY = Pattern.compile("[A-Za-z_][A-Za-z0-9_]{0,63}");

    record Target(String host, int port, boolean tls, String user, String password) {
        @Override
        public String toString() {
            return "Target[" + host + ":" + port + "]";
        }
    }

    record Message(String destination, boolean topic, String text, String correlationId, String messageId, Map<String, String> properties) {
    }

    /** Sends one persistent message and returns once the broker has accepted it; implemented over the client library, faked in tests. */
    interface Sender {
        void send(Target target, Message message) throws Exception;
    }

    private final Set<String> brokers;
    private final List<String> prefixes;
    final Sender sender;

    Jms(Set<String> brokers, List<String> prefixes, Sender sender) {
        this.brokers = brokers;
        this.prefixes = prefixes;
        this.sender = sender;
    }

    static Jms fromEnvironment() {
        return new Jms(Set.copyOf(list(System.getenv(BROKERS_ENV))), list(System.getenv(PREFIXES_ENV)), new ArtemisSender());
    }

    private static List<String> list(String v) {
        return v == null ? List.of() : Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList();
    }

    Target target(Map<String, Object> config) throws ConnectorException {
        if (brokers.isEmpty() || prefixes.isEmpty()) {
            throw new ConnectorException("jms endpoints are not enabled (the operator sets " + BROKERS_ENV + " and " + PREFIXES_ENV + ")", false);
        }
        if (!(config.get("host") instanceof String host) || !HOST.matcher(host).matches()) {
            throw new ConnectorException("host is needed (a plain host name or address)", false);
        }
        boolean tls = config.get("tls") == null || Boolean.parseBoolean(String.valueOf(config.get("tls")));
        int port = 61616;
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
        if (!brokers.contains(host + ":" + port)) {
            throw new ConnectorException("broker " + host + ":" + port + " is not permitted by the operator", false);
        }
        if (!(config.get("user") instanceof String user) || user.isBlank() || !(config.get("password") instanceof String pw) || pw.isEmpty()) {
            throw new ConnectorException("user and password are needed", false);
        }
        return new Target(host, port, tls, user, pw);
    }

    String destination(Map<String, Object> config) throws ConnectorException {
        if (!(config.get("destination") instanceof String d) || !NAME.matcher(d).matches() || prefixes.stream().noneMatch(d::startsWith)) {
            throw new ConnectorException("destination must be a plain name starting with a permitted prefix", false);
        }
        return d;
    }

    static boolean topic(Map<String, Object> config) throws ConnectorException {
        Object t = config.get("destinationType");
        if (t == null || "queue".equals(t)) {
            return false;
        }
        if ("topic".equals(t)) {
            return true;
        }
        throw new ConnectorException("destinationType must be queue or topic", false);
    }

    static boolean propertyName(String k) {
        return PROPERTY.matcher(k).matches();
    }

    static ConnectorException fail(String what, Throwable e, boolean retryable) {
        return new ConnectorException(what + " (" + e.getClass().getSimpleName() + ")", retryable);
    }

    static boolean transientFailure(Throwable e) {
        for (Throwable t = e; t != null; t = t.getCause() == t ? null : t.getCause()) {
            String n = t.getClass().getSimpleName();
            if (t instanceof java.net.SocketTimeoutException || t instanceof java.net.ConnectException || t instanceof java.net.UnknownHostException
                    || t instanceof java.io.EOFException || n.contains("Timeout") || n.contains("NotConnected") || n.contains("Unreachable")) {
                return true;
            }
        }
        return false;
    }
}
