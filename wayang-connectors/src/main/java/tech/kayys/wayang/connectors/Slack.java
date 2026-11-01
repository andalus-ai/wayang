package tech.kayys.wayang.connectors;

import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WebhookProvider;

/**
 * Slack: Events API webhooks and {@code chat.postMessage}.
 *
 * <p>Webhook config: {@code signingSecret} (use {@code ${secret:...}}), <b>{@code allowedChannels} (required)</b>. Requests
 * older or newer than 5 minutes are rejected (replay protection). Bot messages and message subtypes (edits, joins) are
 * dropped so the assistant cannot answer itself. Event: {@code type, channel, user, text, ts, threadTs}.
 *
 * <p>Connector {@code slack}: config {@code botToken} ({@code xoxb-...}), optional {@code baseUrl}; inputs {@code channel},
 * {@code text}, optional {@code threadTs}. Output {@code ts}, {@code channel}.
 */
public final class Slack {

    private static final Duration TOLERANCE = Duration.ofMinutes(5);
    private static final int MAX_TEXT = 40_000;
    private static final String DEFAULT_BASE = "https://slack.com";

    private Slack() {
    }

    public static final class Webhook implements WebhookProvider {
        @Override
        public void verify(Map<String, Object> config, Map<String, String> headers, byte[] body, Instant now) throws Rejected {
            if (!(config.get("signingSecret") instanceof String secret) || secret.isBlank()) {
                throw new Rejected("slack webhook needs a signingSecret");
            }
            String ts = headers.get("x-slack-request-timestamp");
            String sig = headers.get("x-slack-signature");
            if (ts == null || sig == null) {
                throw new Rejected("missing Slack signature headers");
            }
            long seconds;
            try {
                seconds = Long.parseLong(ts.trim());
            } catch (NumberFormatException e) {
                throw new Rejected("bad timestamp");
            }
            if (Duration.between(Instant.ofEpochSecond(seconds), now).abs().compareTo(TOLERANCE) > 0) {
                throw new Rejected("stale or future request");
            }
            byte[] prefix = ("v0:" + ts.trim() + ":").getBytes(StandardCharsets.UTF_8);
            byte[] base = new byte[prefix.length + body.length];
            System.arraycopy(prefix, 0, base, 0, prefix.length);
            System.arraycopy(body, 0, base, prefix.length, body.length);
            if (!Hmac.same("v0=" + Hmac.sha256Hex(secret, base), sig.trim())) {
                throw new Rejected("bad signature");
            }
        }

        @Override
        public Optional<String> handshake(Map<String, Object> config, Map<String, String> query, Map<String, Object> json) {
            if (json != null && "url_verification".equals(json.get("type")) && json.get("challenge") instanceof String c) {
                return Optional.of(c);
            }
            return Optional.empty();
        }

        @Override
        public List<Event> events(Map<String, Object> config, Map<String, Object> json) {
            Set<String> allowed = new HashSet<>();
            if (config.get("allowedChannels") instanceof List<?> l) {
                l.forEach(o -> allowed.add(String.valueOf(o)));
            }
            List<Event> out = new ArrayList<>();
            if (allowed.isEmpty() || json == null || !"event_callback".equals(json.get("type"))
                    || !(json.get("event") instanceof Map<?, ?> ev)) {
                return out;
            }
            Object type = ev.get("type");
            if (!"message".equals(type) && !"app_mention".equals(type)) {
                return out;
            }
            if (ev.get("bot_id") != null || ev.get("subtype") != null || !(ev.get("text") instanceof String text)
                    || !allowed.contains(String.valueOf(ev.get("channel")))) {
                return out;
            }
            Map<String, Object> d = new LinkedHashMap<>();
            d.put("type", type);
            d.put("channel", ev.get("channel"));
            d.put("user", ev.get("user"));
            d.put("text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
            d.put("ts", ev.get("ts"));
            d.put("threadTs", ev.get("thread_ts"));
            String id = json.get("event_id") instanceof String e ? e : "slack:" + ev.get("channel") + ":" + ev.get("ts");
            out.add(new Event(id, d));
            return out;
        }
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("slack needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            if (!(config.get("botToken") instanceof String token) || !token.startsWith("xox") || token.length() < 20) {
                throw new ServiceHandler.ConnectorException("slack needs a valid 'botToken' (use ${secret:NAME})", false);
            }
            if (!(inputs.get("channel") instanceof String channel) || channel.isBlank()) {
                throw new ServiceHandler.ConnectorException("slack needs 'channel'", false);
            }
            if (!(inputs.get("text") instanceof String text) || text.isBlank()) {
                throw new ServiceHandler.ConnectorException("slack needs non-empty 'text'", false);
            }
            if (text.length() > MAX_TEXT) {
                throw new ServiceHandler.ConnectorException("Text longer than " + MAX_TEXT + " characters", false);
            }
            String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b.replaceAll("/+$", "") : DEFAULT_BASE;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("channel", channel);
            body.put("text", text);
            if (inputs.get("threadTs") instanceof String t && !t.isBlank()) {
                body.put("thread_ts", t);
            }
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("url", base + "/api/chat.postMessage");
            cfg.put("method", "POST");
            cfg.put("headers", Map.of("Authorization", "Bearer " + token));
            Map<String, Object> res = http.call(cfg, Map.of("body", body));
            // Slack answers 200 even for errors: {"ok":false,"error":"..."}
            if (!(res.get("body") instanceof Map<?, ?> m) || !Boolean.TRUE.equals(m.get("ok"))) {
                Object err = res.get("body") instanceof Map<?, ?> m2 ? m2.get("error") : null;
                boolean retry = "ratelimited".equals(err) || "service_unavailable".equals(err) || "internal_error".equals(err);
                throw new ServiceHandler.ConnectorException("Slack error: " + (err == null ? "unknown" : String.valueOf(err).replaceAll("[^A-Za-z0-9_]", "?")), retry);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("ts", m.get("ts"));
            out.put("channel", m.get("channel"));
            return out;
        }
    }
}
