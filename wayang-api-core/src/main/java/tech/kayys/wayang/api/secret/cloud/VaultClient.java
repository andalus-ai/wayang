package tech.kayys.wayang.api.secret.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Supplier;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.json.Json;

/**
 * A small HashiCorp Vault client: KV version 2 reads, with a static token or AppRole login. Operator-configured, so it is not
 * behind the SSRF guard that user-supplied URLs get; in exchange it insists on https (loopback excepted for local development),
 * never follows redirects, caps the response, and never puts a token, role secret or response body into an error.
 */
public class VaultClient {

    public static final int MAX_RESPONSE_BYTES = 256 * 1024;
    private static final Pattern SEGMENT = Pattern.compile("[A-Za-z0-9_][A-Za-z0-9_.-]{0,199}");

    /** Thrown for any failure talking to Vault; the message is safe to log. */
    public static final class VaultException extends RuntimeException {
        private final int status;

        public VaultException(String message, int status) {
            super(message);
            this.status = status;
        }

        /** HTTP status, or 0 when there was none (network, parse). 404 means "no such secret". */
        public int status() {
            return status;
        }
    }

    private final URI base;
    private final String namespace;
    private final Supplier<String> tokens;
    private final HttpClient http;
    private final Duration timeout;

    /** @param tokens supplies the current client token (see {@link #appRole} for a self-renewing one) */
    public VaultClient(String address, String namespace, Supplier<String> tokens, Duration timeout, boolean allowInsecure) {
        URI u = URI.create(address.endsWith("/") ? address.substring(0, address.length() - 1) : address);
        boolean loopback = u.getHost() != null && (u.getHost().equals("localhost") || u.getHost().equals("127.0.0.1") || u.getHost().equals("[::1]"));
        if (!"https".equals(u.getScheme()) && !(("http".equals(u.getScheme())) && (allowInsecure || loopback))) {
            throw new IllegalArgumentException("Vault address must be https (http only for loopback or with allowInsecure)");
        }
        if (u.getUserInfo() != null || u.getHost() == null || u.getQuery() != null || u.getFragment() != null) {
            throw new IllegalArgumentException("Vault address must be a plain scheme://host[:port][/prefix]");
        }
        this.base = u;
        this.namespace = namespace;
        this.tokens = tokens;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    /**
     * Reads a KV v2 secret.
     *
     * @return the string fields of the secret (other types are skipped)
     * @throws VaultException 404 when the secret does not exist
     */
    public Map<String, String> readKv2(String mount, String path) {
        String url = base + "/v1/" + segments(mount) + "/data/" + segments(path);
        Object json = get(url);
        if (json instanceof Map<?, ?> top && top.get("data") instanceof Map<?, ?> outer && outer.get("data") instanceof Map<?, ?> data) {
            Map<String, String> out = new LinkedHashMap<>();
            data.forEach((k, v) -> {
                if (v instanceof String s) {
                    out.put(String.valueOf(k), s);
                }
            });
            return out;
        }
        throw new VaultException("Vault answered with something that is not a KV v2 secret", 0);
    }

    private static String segments(String path) {
        StringBuilder sb = new StringBuilder();
        for (String seg : path.split("/", -1)) {
            if (!SEGMENT.matcher(seg).matches() || seg.contains("..")) {
                throw new IllegalArgumentException("Bad Vault path segment");
            }
            if (sb.length() > 0) {
                sb.append('/');
            }
            sb.append(URLEncoder.encode(seg, StandardCharsets.UTF_8));
        }
        return sb.toString();
    }

    private Object get(String url) {
        String token = tokens.get();
        if (token == null || token.isBlank() || token.indexOf('\n') >= 0 || token.indexOf('\r') >= 0) {
            throw new VaultException("No usable Vault token", 0);
        }
        HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(url)).timeout(timeout).header("X-Vault-Token", token.strip())
                .header("Accept", "application/json").GET();
        if (namespace != null && !namespace.isBlank()) {
            rb.header("X-Vault-Namespace", namespace);
        }
        return send(rb.build());
    }

    private Object send(HttpRequest req) {
        try {
            HttpResponse<InputStream> r = http.send(req, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                byte[] body = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (r.statusCode() / 100 != 2) {
                    throw new VaultException("Vault answered " + r.statusCode(), r.statusCode());
                }
                if (body.length > MAX_RESPONSE_BYTES) {
                    throw new VaultException("Vault response is larger than " + MAX_RESPONSE_BYTES + " bytes", 0);
                }
                try {
                    return Json.read(new String(body, StandardCharsets.UTF_8));
                } catch (RuntimeException e) {
                    throw new VaultException("Vault answered with invalid JSON", 0);
                }
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new VaultException("Interrupted while calling Vault", 0);
        } catch (IOException e) {
            throw new VaultException("Cannot reach Vault (" + e.getClass().getSimpleName() + ")", 0);
        }
    }

    // ---------------------------------------------------------------- tokens

    public static Supplier<String> staticToken(String token) {
        return () -> token;
    }

    /** A token read from a file on every use, so a sidecar that renews it needs no restart. */
    public static Supplier<String> tokenFile(java.nio.file.Path file) {
        return () -> {
            try {
                return java.nio.file.Files.readString(file).strip();
            } catch (IOException e) {
                throw new VaultException("Cannot read the Vault token file", 0);
            }
        };
    }

    /**
     * AppRole login, renewed at 80% of the lease and again after a 403. {@code secretId} is a supplier so it can come from a
     * file. The returned supplier is thread-safe.
     */
    public static Supplier<String> appRole(String address, String namespace, String mountPath, String roleId, Supplier<String> secretId,
            Duration timeout, boolean allowInsecure, Clock clock) {
        VaultClient login = new VaultClient(address, namespace, () -> "login", timeout, allowInsecure);
        return new Supplier<>() {
            private String token;
            private Instant renewAt = Instant.MIN;

            @Override
            public synchronized String get() {
                if (token != null && clock.instant().isBefore(renewAt)) {
                    return token;
                }
                String body = Json.write(Map.of("role_id", roleId, "secret_id", secretId.get()));
                HttpRequest.Builder rb = HttpRequest.newBuilder(URI.create(login.base + "/v1/auth/" + segments(mountPath) + "/login"))
                        .timeout(timeout).header("Content-Type", "application/json").header("Accept", "application/json")
                        .POST(HttpRequest.BodyPublishers.ofString(body));
                if (namespace != null && !namespace.isBlank()) {
                    rb.header("X-Vault-Namespace", namespace);
                }
                Object json = login.send(rb.build());
                if (json instanceof Map<?, ?> top && top.get("auth") instanceof Map<?, ?> auth && auth.get("client_token") instanceof String t
                        && !t.isBlank()) {
                    long lease = auth.get("lease_duration") instanceof Number n ? n.longValue() : 60;
                    token = t;
                    renewAt = clock.instant().plusSeconds(Math.max(1, lease * 8 / 10));
                    return token;
                }
                throw new VaultException("Vault login did not return a token", 0);
            }
        };
    }
}
