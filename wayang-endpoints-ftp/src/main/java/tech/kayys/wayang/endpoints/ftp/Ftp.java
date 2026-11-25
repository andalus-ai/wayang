package tech.kayys.wayang.endpoints.ftp;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Shared rules of the FTP/FTPS endpoints. Off until the operator lists the permitted servers in {@code WAYANG_ENDPOINTS_FTP_HOSTS}
 * (comma-separated {@code host:port}; port 21 when a node omits it). A node names {@code host}, {@code port}, {@code user} and
 * {@code password} (use {@code ${secret:NAME}}). TLS (explicit FTPS: AUTH TLS, protected data channel, certificate and host name verified) is on
 * unless the node sets {@code tls: false}; plain FTP sends the password and the data in the clear, so it also needs the operator to set
 * {@code WAYANG_ENDPOINTS_FTP_ALLOW_PLAIN=true}. Passive mode only. Paths are relative to the login directory, may not be empty segments,
 * {@code ..} or contain backslashes or NUL, and never leave it. Secrets are never echoed.
 */
final class Ftp {

    static final String HOSTS_ENV = "WAYANG_ENDPOINTS_FTP_HOSTS";
    static final String PLAIN_ENV = "WAYANG_ENDPOINTS_FTP_ALLOW_PLAIN";
    static final int MAX_READ_BYTES = 8 << 20;
    static final int HARD_MAX_BYTES = 32 << 20;
    static final int MAX_LIST = 1000;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]{1,253}");

    record Entry(String name, boolean file, long size, long mtimeSeconds) {
    }

    /** One open FTP session; implemented over a library in production and by a fake in tests. */
    interface Session extends AutoCloseable {
        List<Entry> list(String dir) throws Exception;

        /** @return the content, or null when it is larger than max */
        byte[] read(String path, int max) throws Exception;

        void write(String path, byte[] data, boolean overwrite) throws Exception;

        void delete(String path) throws Exception;

        @Override
        void close();
    }

    /** What a node asked for, validated. {@code secret} fields are only ever handed to the library. */
    record Target(String host, int port, boolean tls, String user, String password) {
        @Override
        public String toString() {
            return "Target[" + host + ":" + port + "]";
        }
    }

    interface Opener {
        Session open(Target target) throws Exception;
    }

    private final Set<String> hosts;
    private final boolean allowPlain;
    final Opener opener;

    Ftp(Set<String> hosts, boolean allowPlain, Opener opener) {
        this.hosts = hosts;
        this.allowPlain = allowPlain;
        this.opener = opener;
    }

    static Ftp fromEnvironment() {
        String v = System.getenv(HOSTS_ENV);
        Set<String> h = v == null ? Set.of() : Set.copyOf(Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
        return new Ftp(h, "true".equalsIgnoreCase(System.getenv(PLAIN_ENV)), new CommonsNetOpener());
    }

    Target target(Map<String, Object> config) throws ConnectorException {
        if (hosts.isEmpty()) {
            throw new ConnectorException("ftp endpoints are not enabled (the operator sets " + HOSTS_ENV + ")", false);
        }
        if (!(config.get("host") instanceof String host) || !HOST.matcher(host).matches()) {
            throw new ConnectorException("host is needed (a plain host name or address)", false);
        }
        int port = 21;
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
            throw new ConnectorException("server " + host + ":" + port + " is not permitted by the operator", false);
        }
        if (!(config.get("user") instanceof String user) || user.isBlank() || user.length() > 128) {
            throw new ConnectorException("user is needed", false);
        }
        boolean tls = config.get("tls") == null || Boolean.parseBoolean(String.valueOf(config.get("tls")));
        if (!tls && !allowPlain) {
            throw new ConnectorException("plain ftp (tls: false) is not permitted by the operator (" + PLAIN_ENV + ")", false);
        }
        if (!(config.get("password") instanceof String password) || password.isEmpty()) {
            throw new ConnectorException("password is needed", false);
        }
        return new Target(host, port, tls, user, password);
    }

    /** @return the path unchanged when it is a safe relative path ({@code ""} is the home directory when allowEmpty) */
    static String path(Object raw, boolean allowEmpty) throws ConnectorException {
        String p = raw == null ? "" : String.valueOf(raw);
        if (p.isEmpty() && allowEmpty) {
            return ".";
        }
        if (p.isEmpty() || p.length() > 1024 || p.startsWith("/") || p.indexOf('\\') >= 0 || p.indexOf('\0') >= 0 || p.matches("^[A-Za-z]:.*")) {
            throw new ConnectorException("path must be relative to the login directory (no leading /, no backslash)", false);
        }
        for (String seg : p.split("/", -1)) {
            if (seg.isEmpty() || seg.equals("..") || (seg.equals(".") && !p.equals("."))) {
                throw new ConnectorException("path may not contain empty, . or .. segments", false);
            }
        }
        return p;
    }

    /** A failure that carries the exception type only: server messages can contain paths, users or banners. */
    static ConnectorException fail(String what, Throwable e, boolean retryable) {
        return new ConnectorException(what + " (" + e.getClass().getSimpleName() + ")", retryable);
    }

    static boolean transientFailure(Throwable e) {
        return e instanceof java.net.SocketTimeoutException || e instanceof java.net.ConnectException || e instanceof java.net.UnknownHostException
                || e instanceof java.io.EOFException || e.getClass().getSimpleName().contains("Timeout") || e.getClass().getSimpleName().contains("Transport");
    }
}
