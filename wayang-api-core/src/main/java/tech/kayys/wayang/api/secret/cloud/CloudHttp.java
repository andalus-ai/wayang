package tech.kayys.wayang.api.secret.cloud;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;

/**
 * The one HTTP path the cloud secret backends share. The endpoints are operator-configured (so not behind the SSRF guard that
 * user-supplied URLs get); in exchange https is required (loopback and an explicit flag excepted), redirects are never followed,
 * the response is capped, and nothing from a request or response ends up in an error.
 */
public final class CloudHttp {

    public static final int MAX_RESPONSE_BYTES = 256 * 1024;

    public record Response(int status, byte[] body) {
    }

    private final HttpClient http;
    private final Duration timeout;

    public CloudHttp(Duration timeout) {
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(timeout).build();
    }

    /** Validates an operator-supplied base URL: {@code https://host[:port][/prefix]}, http only for loopback or with the flag. */
    public static URI endpoint(String url, boolean allowInsecure, String what) {
        URI u;
        try {
            u = URI.create(url.endsWith("/") ? url.substring(0, url.length() - 1) : url);
        } catch (RuntimeException e) {
            throw new IllegalArgumentException(what + " is not a valid URL");
        }
        String h = u.getHost();
        boolean loopback = h != null && (h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]"));
        if (!"https".equals(u.getScheme()) && !("http".equals(u.getScheme()) && (allowInsecure || loopback))) {
            throw new IllegalArgumentException(what + " must be https (http only for loopback or with allow-insecure)");
        }
        if (u.getUserInfo() != null || h == null || u.getQuery() != null || u.getFragment() != null) {
            throw new IllegalArgumentException(what + " must be a plain scheme://host[:port][/prefix]");
        }
        return u;
    }

    public Response send(String method, URI uri, Map<String, String> headers, byte[] body) {
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(timeout);
        headers.forEach((k, v) -> {
            if (v.indexOf('\n') >= 0 || v.indexOf('\r') >= 0) {
                throw new CloudException("Refusing a header value with a line break", 0);
            }
            if (!k.equalsIgnoreCase("host")) { // the client sets Host itself, from the URI
                rb.header(k, v);
            }
        });
        rb.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofByteArray(body));
        try {
            HttpResponse<InputStream> r = http.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream in = r.body()) {
                byte[] b = in.readNBytes(MAX_RESPONSE_BYTES + 1);
                if (b.length > MAX_RESPONSE_BYTES) {
                    throw new CloudException("Response is larger than " + MAX_RESPONSE_BYTES + " bytes", 0);
                }
                return new Response(r.statusCode(), b);
            }
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new CloudException("Interrupted while calling a cloud service", 0);
        } catch (IOException e) {
            throw new CloudException("Cannot reach the cloud service (" + e.getClass().getSimpleName() + ")", 0);
        }
    }
}
