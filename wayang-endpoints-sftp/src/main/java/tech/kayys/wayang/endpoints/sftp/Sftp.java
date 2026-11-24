package tech.kayys.wayang.endpoints.sftp;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler.ConnectorException;

/**
 * Shared rules of the SFTP endpoints. Off until the operator lists the permitted servers in {@code WAYANG_ENDPOINTS_SFTP_HOSTS}
 * (comma-separated {@code host:port}; port 22 when a node omits it). A node names {@code host}, {@code port}, {@code user}, one of
 * {@code password} or {@code privateKey} (PEM text, optional {@code passphrase}; use {@code ${secret:NAME}} for all secrets) and the
 * server's {@code hostKey} fingerprint ({@code SHA256:...} as printed by {@code ssh-keygen -lf} or {@code ssh-keyscan | ssh-keygen -lf -}).
 * The host key is mandatory: there is no trust-on-first-use and no way to switch verification off. Paths are relative to the account's
 * home, may not be empty segments, {@code ..} or contain backslashes or NUL, and never leave it. Secrets are never echoed.
 */
final class Sftp {

    static final String HOSTS_ENV = "WAYANG_ENDPOINTS_SFTP_HOSTS";
    static final int MAX_READ_BYTES = 8 << 20;
    static final int HARD_MAX_BYTES = 32 << 20;
    static final int MAX_LIST = 1000;
    private static final Pattern HOST = Pattern.compile("[A-Za-z0-9._-]{1,253}");
    private static final Pattern FINGERPRINT = Pattern.compile("SHA256:[A-Za-z0-9+/]{43}=?");

    record Entry(String name, boolean file, long size, long mtimeSeconds) {
    }

    /** One open SFTP session; implemented over a library in production and by a fake in tests. */
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
    record Target(String host, int port, String user, String password, String privateKey, String passphrase, String hostKey) {
        @Override
        public String toString() {
            return "Target[" + host + ":" + port + "]";
        }
    }

    interface Opener {
        Session open(Target target) throws Exception;
    }

    private final Set<String> hosts;
    final Opener opener;

    Sftp(Set<String> hosts, Opener opener) {
        this.hosts = hosts;
        this.opener = opener;
    }

    static Sftp fromEnvironment() {
        String v = System.getenv(HOSTS_ENV);
        Set<String> h = v == null ? Set.of() : Set.copyOf(Arrays.stream(v.split(",")).map(String::trim).filter(x -> !x.isEmpty()).toList());
        return new Sftp(h, new SshjOpener());
    }

    Target target(Map<String, Object> config) throws ConnectorException {
        if (hosts.isEmpty()) {
            throw new ConnectorException("sftp endpoints are not enabled (the operator sets " + HOSTS_ENV + ")", false);
        }
        if (!(config.get("host") instanceof String host) || !HOST.matcher(host).matches()) {
            throw new ConnectorException("host is needed (a plain host name or address)", false);
        }
        int port = 22;
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
        if (!(config.get("hostKey") instanceof String hk) || !FINGERPRINT.matcher(hk.trim()).matches()) {
            throw new ConnectorException("hostKey is needed: the server's SHA256 fingerprint (SHA256:...)", false);
        }
        String password = config.get("password") instanceof String p && !p.isEmpty() ? p : null;
        String key = config.get("privateKey") instanceof String k && !k.isBlank() ? k : null;
        if ((password == null) == (key == null)) {
            throw new ConnectorException("give exactly one of password or privateKey", false);
        }
        String pass = config.get("passphrase") instanceof String s && !s.isEmpty() ? s : null;
        return new Target(host, port, user, password, key, pass, hk.trim());
    }

    /** @return the path unchanged when it is a safe relative path ({@code ""} is the home directory when allowEmpty) */
    static String path(Object raw, boolean allowEmpty) throws ConnectorException {
        String p = raw == null ? "" : String.valueOf(raw);
        if (p.isEmpty() && allowEmpty) {
            return ".";
        }
        if (p.isEmpty() || p.length() > 1024 || p.startsWith("/") || p.indexOf('\\') >= 0 || p.indexOf('\0') >= 0 || p.matches("^[A-Za-z]:.*")) {
            throw new ConnectorException("path must be relative to the account's home (no leading /, no backslash)", false);
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
