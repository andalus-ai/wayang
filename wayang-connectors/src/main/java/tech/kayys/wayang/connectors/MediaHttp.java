package tech.kayys.wayang.connectors;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Map;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/** Bounded HTTP (raw bytes in and out) for the media connectors: every URL goes through the same {@link UrlGuard} as the other connectors; no redirects are followed. */
public final class MediaHttp {

    private final UrlGuard guard;
    private final HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();

    public MediaHttp(UrlGuard guard) {
        this.guard = guard;
    }

    public URI uri(String base, String path) throws ServiceHandler.ConnectorException {
        try {
            return guard.check(base.replaceAll("/+$", "") + path);
        } catch (IllegalArgumentException e) {
            throw new ServiceHandler.ConnectorException(e.getMessage(), false);
        }
    }

    public byte[] send(HttpRequest.Builder rb, long timeoutMs, long maxBytes) throws ServiceHandler.ConnectorException {
        try {
            HttpResponse<InputStream> r = client.send(rb.timeout(Duration.ofMillis(timeoutMs)).build(), HttpResponse.BodyHandlers.ofInputStream());
            byte[] raw;
            try (InputStream in = r.body()) {
                raw = in.readNBytes((int) maxBytes + 1);
            }
            int s = r.statusCode();
            if (s < 200 || s > 299) {
                boolean retry = s == 408 || s == 425 || s == 429 || s >= 500;
                String snip = new String(raw, 0, Math.min(raw.length, 200), StandardCharsets.UTF_8).replaceAll("[\\r\\n\\p{Cntrl}]", " ");
                throw new ServiceHandler.ConnectorException("HTTP " + s + (snip.isBlank() ? "" : ": " + snip), retry);
            }
            if (raw.length > maxBytes) {
                throw new ServiceHandler.ConnectorException("Response larger than " + (maxBytes >> 20) + " MiB", false);
            }
            return raw;
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ServiceHandler.ConnectorException("Request timed out", true);
        } catch (IOException e) {
            throw new ServiceHandler.ConnectorException("Request failed: " + e.getClass().getSimpleName(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceHandler.ConnectorException("Interrupted", false);
        }
    }

    public Object postJson(URI uri, Map<String, String> headers, Object body, long timeoutMs, long maxBytes) throws ServiceHandler.ConnectorException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).header("Content-Type", "application/json").header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(Json.write(body)));
        headers.forEach(rb::header);
        return parse(send(rb, timeoutMs, maxBytes));
    }

    /** POST of a raw body (multipart, for example) whose answer is JSON. */
    public Object postBytes(URI uri, Map<String, String> headers, String contentType, byte[] body, long timeoutMs, long maxBytes)
            throws ServiceHandler.ConnectorException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).header("Content-Type", contentType).header("Accept", "application/json")
                .POST(HttpRequest.BodyPublishers.ofByteArray(body));
        headers.forEach(rb::header);
        return parse(send(rb, timeoutMs, maxBytes));
    }

    /** Checks a URL the provider handed back (for example an upload URL) against the same guard. */
    public URI check(String url) throws ServiceHandler.ConnectorException {
        try {
            return guard.check(url);
        } catch (IllegalArgumentException e) {
            throw new ServiceHandler.ConnectorException(e.getMessage(), false);
        }
    }

    /** POST of raw bytes whose answer is not parsed (plain-text acknowledgements). No default headers are added. */
    public void postRaw(URI uri, String contentType, byte[] body, long timeoutMs) throws ServiceHandler.ConnectorException {
        send(HttpRequest.newBuilder(uri).header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body)), timeoutMs, 4096);
    }

    public Object getJson(URI uri, Map<String, String> headers, long timeoutMs) throws ServiceHandler.ConnectorException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).header("Accept", "application/json").GET();
        headers.forEach(rb::header);
        return parse(send(rb, timeoutMs, 1 << 20));
    }

    public byte[] getBytes(URI uri, Map<String, String> headers, long timeoutMs, long maxBytes) throws ServiceHandler.ConnectorException {
        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).GET();
        headers.forEach(rb::header);
        return send(rb, timeoutMs, maxBytes);
    }

    private static Object parse(byte[] raw) throws ServiceHandler.ConnectorException {
        try {
            return Json.read(new String(raw, StandardCharsets.UTF_8));
        } catch (RuntimeException e) {
            throw new ServiceHandler.ConnectorException("The provider answered with something other than JSON", false);
        }
    }
}
