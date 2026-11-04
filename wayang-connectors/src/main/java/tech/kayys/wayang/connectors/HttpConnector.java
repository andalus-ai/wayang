package tech.kayys.wayang.connectors;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Connector {@code http}. Node config (static, secrets already resolved): {@code url} (required), {@code method}
 * (GET default; GET/POST/PUT/PATCH/DELETE), {@code headers} (object), {@code query} (object),
 * {@code timeoutMs} (default 10000, max 60000), {@code maxResponseBytes} (default 1 MiB, max 10 MiB).
 * Node inputs (evaluated per run): {@code body} (object/array sent as JSON, or a string sent as text),
 * {@code query}, {@code headers} (merged over the config ones).
 * Output: {@code status}, {@code ok}, {@code body} (parsed JSON when the response is JSON, else text),
 * {@code contentType}, {@code headers} (first value of each response header, names lower-cased).
 * Failures: connection problems, timeouts, 408, 425, 429 and 5xx are retryable; other non-2xx are not. Redirects
 * are never followed. 2xx responses larger than the limit fail non-retryably. With {@code returnErrorBody: true} a non-2xx
 * answer is returned (with {@code ok: false}) instead of thrown, for protocols such as SOAP that carry their error in the body.
 */
public final class HttpConnector implements ServiceHandler.Connector {

    private static final Set<String> METHODS = Set.of("GET", "POST", "PUT", "PATCH", "DELETE");
    private static final Set<String> FORBIDDEN_HEADERS = Set.of("host", "content-length", "connection", "transfer-encoding",
            "upgrade", "expect");

    private final UrlGuard guard;
    private final HttpClient client;

    public HttpConnector(UrlGuard guard) {
        this.guard = guard;
        this.client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER)
                .connectTimeout(Duration.ofSeconds(10)).build();
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        return call(Map.of(), inputs);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        if (!(config.get("url") instanceof String url) || url.isBlank()) {
            throw new ServiceHandler.ConnectorException("http connector needs a 'url' in the node configuration", false);
        }
        String method = String.valueOf(config.getOrDefault("method", "GET")).toUpperCase(Locale.ROOT);
        if (!METHODS.contains(method)) {
            throw new ServiceHandler.ConnectorException("Unsupported method " + method, false);
        }
        long timeoutMs = bounded(config.get("timeoutMs"), 10_000, 100, 60_000, "timeoutMs");
        int maxBytes = (int) bounded(config.get("maxResponseBytes"), 1 << 20, 1, 10 << 20, "maxResponseBytes");

        Map<String, String> query = new LinkedHashMap<>();
        stringMap(config.get("query"), query, "query");
        stringMap(inputs.get("query"), query, "query");
        Map<String, String> headers = new LinkedHashMap<>();
        stringMap(config.get("headers"), headers, "headers");
        stringMap(inputs.get("headers"), headers, "headers");

        URI uri;
        try {
            uri = guard.check(withQuery(url, query));
        } catch (IllegalArgumentException e) {
            throw new ServiceHandler.ConnectorException(e.getMessage(), false);
        }

        HttpRequest.Builder rb = HttpRequest.newBuilder(uri).timeout(Duration.ofMillis(timeoutMs));
        for (var h : headers.entrySet()) {
            String name = h.getKey();
            if (FORBIDDEN_HEADERS.contains(name.toLowerCase(Locale.ROOT)) || name.contains("\r") || name.contains("\n")
                    || h.getValue().contains("\r") || h.getValue().contains("\n")) {
                throw new ServiceHandler.ConnectorException("Header '" + name.replaceAll("[\\r\\n]", "?") + "' is not allowed", false);
            }
            rb.header(name, h.getValue());
        }
        Object body = inputs.get("body");
        HttpRequest.BodyPublisher pub = HttpRequest.BodyPublishers.noBody();
        if (body != null && !method.equals("GET")) {
            if (body instanceof byte[] raw) {
                if (headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-type"))) {
                    throw new ServiceHandler.ConnectorException("A binary body needs a Content-Type header", false);
                }
                pub = HttpRequest.BodyPublishers.ofByteArray(raw);
            } else if (body instanceof String s) {
                pub = HttpRequest.BodyPublishers.ofString(s);
            } else {
                if (headers.keySet().stream().noneMatch(k -> k.equalsIgnoreCase("content-type"))) {
                    rb.header("Content-Type", "application/json");
                }
                pub = HttpRequest.BodyPublishers.ofString(Json.write(body));
            }
        }
        rb.method(method, pub);

        HttpResponse<InputStream> resp;
        try {
            resp = client.send(rb.build(), HttpResponse.BodyHandlers.ofInputStream());
        } catch (java.net.http.HttpTimeoutException e) {
            throw new ServiceHandler.ConnectorException("Request timed out after " + timeoutMs + " ms", true);
        } catch (IOException e) {
            throw new ServiceHandler.ConnectorException("Request failed: " + e.getClass().getSimpleName(), true);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new ServiceHandler.ConnectorException("Interrupted", false);
        }
        int status = resp.statusCode();
        byte[] raw;
        try (InputStream in = resp.body()) {
            raw = in.readNBytes(maxBytes + 1);
        }
        String contentType = resp.headers().firstValue("content-type").orElse("");
        boolean errorBody = Boolean.TRUE.equals(config.get("returnErrorBody"));
        if ((status < 200 || status > 299) && !errorBody) {
            boolean retry = status == 408 || status == 425 || status == 429 || status >= 500;
            throw new ServiceHandler.ConnectorException("HTTP " + status + snippet(raw), retry);
        }
        if (raw.length > maxBytes) {
            throw new ServiceHandler.ConnectorException("Response larger than " + maxBytes + " bytes", false);
        }
        String text = new String(raw, StandardCharsets.UTF_8);
        Object parsed = text;
        if (contentType.toLowerCase(Locale.ROOT).contains("json") && !text.isBlank()) {
            try {
                parsed = Json.read(text);
            } catch (RuntimeException e) {
                throw new ServiceHandler.ConnectorException("Response is not valid JSON", false);
            }
        }
        Map<String, Object> out = new LinkedHashMap<>();
        out.put("status", status);
        out.put("ok", status >= 200 && status <= 299);
        out.put("body", parsed);
        out.put("contentType", contentType);
        Map<String, Object> respHeaders = new LinkedHashMap<>();
        resp.headers().map().forEach((k, v) -> {
            String name = k.toLowerCase(Locale.ROOT);
            if (!v.isEmpty() && !name.startsWith("set-cookie")) {
                respHeaders.put(name, v.get(0));
            }
        });
        out.put("headers", respHeaders);
        return out;
    }

    private static String snippet(byte[] raw) {
        if (raw.length == 0) {
            return "";
        }
        String s = new String(raw, 0, Math.min(raw.length, 200), StandardCharsets.UTF_8).replaceAll("\\s+", " ").trim();
        return s.isEmpty() ? "" : ": " + s;
    }

    private static long bounded(Object v, long def, long min, long max, String name) throws ServiceHandler.ConnectorException {
        if (v == null) {
            return def;
        }
        if (!(v instanceof Number n) || n.doubleValue() != Math.rint(n.doubleValue()) || n.longValue() < min || n.longValue() > max) {
            throw new ServiceHandler.ConnectorException(name + " must be an integer between " + min + " and " + max, false);
        }
        return n.longValue();
    }

    private static void stringMap(Object v, Map<String, String> into, String what) throws ServiceHandler.ConnectorException {
        if (v == null) {
            return;
        }
        if (!(v instanceof Map<?, ?> m)) {
            throw new ServiceHandler.ConnectorException(what + " must be an object", false);
        }
        for (var e : m.entrySet()) {
            if (e.getValue() != null) {
                into.put(String.valueOf(e.getKey()), String.valueOf(e.getValue()));
            }
        }
    }

    private static String withQuery(String url, Map<String, String> query) {
        if (query.isEmpty()) {
            return url;
        }
        StringBuilder sb = new StringBuilder(url).append(url.contains("?") ? '&' : '?');
        boolean first = true;
        for (var e : query.entrySet()) {
            if (!first) {
                sb.append('&');
            }
            first = false;
            sb.append(URLEncoder.encode(e.getKey(), StandardCharsets.UTF_8)).append('=')
                    .append(URLEncoder.encode(e.getValue(), StandardCharsets.UTF_8));
        }
        return sb.toString();
    }
}
