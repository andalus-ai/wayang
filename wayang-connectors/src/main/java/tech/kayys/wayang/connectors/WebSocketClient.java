package tech.kayys.wayang.connectors;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.WebSocket;
import java.time.Duration;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.CompletionStage;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.json.Json;

/**
 * Connector {@code websocket}: opens a WebSocket, optionally sends one message, collects up to a number of answers and closes.
 *
 * <p>Config: {@code url} ({@code ws://} or {@code wss://}), {@code headers} (secrets as {@code ${secret:NAME}}), {@code subprotocol},
 * {@code collect} (messages to wait for, 0 to 20, default 1; 0 = send only), {@code waitMs} (default 10000, max 60000),
 * {@code maxBytes} (total answer size, default 1 MiB, max 10 MiB), {@code outputMap}. Input: {@code message} (a string, or an object / array
 * sent as JSON). Output: {@code messages} (list of strings), {@code text} (the last one), {@code json} (the last one parsed, when it is JSON),
 * {@code timedOut} (fewer messages than asked for arrived in time). Binary frames are ignored. Redirects are not followed; the URL guard
 * checks the target as http/https. This is a request/answer exchange, not a long-lived subscription: use a poller for streams.
 */
public final class WebSocketClient implements ServiceHandler.Connector {

    private static final Set<String> FORBIDDEN = Set.of("host", "connection", "upgrade", "content-length", "sec-websocket-key",
            "sec-websocket-version", "sec-websocket-extensions", "sec-websocket-protocol");

    private final UrlGuard guard;

    public WebSocketClient(UrlGuard guard) {
        this.guard = guard;
    }

    @Override
    public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
        throw new ServiceHandler.ConnectorException("websocket needs node configuration (url)", false);
    }

    @Override
    public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
        String raw = Proto.url(config, "websocket");
        URI uri;
        try {
            uri = URI.create(raw);
        } catch (IllegalArgumentException e) {
            throw new ServiceHandler.ConnectorException("websocket url is not valid", false);
        }
        String scheme = uri.getScheme() == null ? "" : uri.getScheme().toLowerCase(Locale.ROOT);
        if (!scheme.equals("ws") && !scheme.equals("wss")) {
            throw new ServiceHandler.ConnectorException("websocket url must start with ws:// or wss://", false);
        }
        try {
            guard.check((scheme.equals("ws") ? "http" : "https") + raw.substring(scheme.length()));
        } catch (IllegalArgumentException e) {
            throw new ServiceHandler.ConnectorException(e.getMessage(), false);
        }
        int collect = number(config.get("collect"), 1, 0, 20, "collect");
        long waitMs = number(config.get("waitMs"), 10_000, 100, 60_000, "waitMs");
        long maxBytes = number(config.get("maxBytes"), 1 << 20, 1024, 10 << 20, "maxBytes");
        String sub = config.get("subprotocol") instanceof String s && !s.isBlank() ? s : null;
        if (sub != null && !sub.matches("[A-Za-z0-9!#$%&'*+.^_`|~-]{1,100}")) {
            throw new ServiceHandler.ConnectorException("websocket subprotocol is not valid", false);
        }
        Object in = inputs == null ? null : inputs.get("message");
        String message = in == null ? null : in instanceof String s ? s : Json.write(in);
        if (message != null && message.length() > (1 << 20)) {
            throw new ServiceHandler.ConnectorException("websocket message is larger than 1 MiB", false);
        }

        List<String> messages = new ArrayList<>();
        CountDownLatch done = new CountDownLatch(collect == 0 ? 0 : 1);
        long[] size = {0};
        boolean[] overflow = {false};
        StringBuilder partial = new StringBuilder();
        WebSocket.Listener listener = new WebSocket.Listener() {
            @Override
            public CompletionStage<?> onText(WebSocket ws, CharSequence data, boolean last) {
                partial.append(data);
                size[0] += data.length();
                if (size[0] > maxBytes) {
                    overflow[0] = true;
                    done.countDown();
                    return null;
                }
                if (last) {
                    synchronized (messages) {
                        messages.add(partial.toString());
                        if (messages.size() >= collect) {
                            done.countDown();
                        }
                    }
                    partial.setLength(0);
                }
                ws.request(1);
                return null;
            }

            @Override
            public CompletionStage<?> onClose(WebSocket ws, int code, String reason) {
                done.countDown();
                return null;
            }

            @Override
            public void onError(WebSocket ws, Throwable error) {
                done.countDown();
            }
        };
        HttpClient client = HttpClient.newBuilder().followRedirects(HttpClient.Redirect.NEVER).connectTimeout(Duration.ofSeconds(10)).build();
        WebSocket.Builder b = client.newWebSocketBuilder().connectTimeout(Duration.ofSeconds(10));
        for (var e : Proto.headers(config).entrySet()) {
            if (FORBIDDEN.contains(e.getKey().toLowerCase(Locale.ROOT))) {
                throw new ServiceHandler.ConnectorException("Header '" + e.getKey() + "' cannot be set on a WebSocket", false);
            }
            b.header(e.getKey(), e.getValue());
        }
        if (sub != null) {
            b.subprotocols(sub);
        }
        WebSocket ws;
        try {
            ws = b.buildAsync(uri, listener).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new ServiceHandler.ConnectorException("The WebSocket connection failed", true);
        }
        boolean timedOut = false;
        try {
            if (message != null) {
                ws.sendText(message, true).get(waitMs, TimeUnit.MILLISECONDS);
            }
            if (collect > 0) {
                timedOut = !done.await(waitMs, TimeUnit.MILLISECONDS);
            }
        } catch (Exception e) {
            throw new ServiceHandler.ConnectorException("The WebSocket exchange failed", true);
        } finally {
            CompletableFuture<WebSocket> closing = ws.sendClose(WebSocket.NORMAL_CLOSURE, "done");
            try {
                closing.get(2, TimeUnit.SECONDS);
            } catch (Exception ignored) {
                ws.abort();
            }
        }
        if (overflow[0]) {
            throw new ServiceHandler.ConnectorException("The WebSocket answer is larger than " + maxBytes + " bytes", false);
        }
        List<String> got;
        synchronized (messages) {
            got = new ArrayList<>(messages);
        }
        if (collect > 0 && got.isEmpty() && timedOut) {
            throw new ServiceHandler.ConnectorException("No WebSocket message arrived within " + waitMs + " ms", true);
        }
        Map<String, Object> out = new LinkedHashMap<>();
        String last = got.isEmpty() ? "" : got.get(got.size() - 1);
        out.put("messages", got);
        out.put("text", last);
        Object json = null;
        String t = last.strip();
        if (t.startsWith("{") || t.startsWith("[")) {
            try {
                json = Json.read(t);
            } catch (RuntimeException ignored) {
                // not JSON after all: text is still there
            }
        }
        out.put("json", json);
        out.put("timedOut", timedOut);
        Proto.outputMap(config, out, out);
        return out;
    }

    private static int number(Object v, int dflt, int min, int max, String what) throws ServiceHandler.ConnectorException {
        if (v == null) {
            return dflt;
        }
        if (!(v instanceof Number n) || n.intValue() < min || n.intValue() > max) {
            throw new ServiceHandler.ConnectorException("websocket " + what + " must be " + min + ".." + max, false);
        }
        return n.intValue();
    }
}
