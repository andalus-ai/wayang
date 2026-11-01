package tech.kayys.wayang.connectors;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Telegram Bot API on top of {@link HttpConnector} (so the SSRF guard, size and time limits apply).
 *
 * <p>Connector {@code telegram} (operation {@code sendMessage}): config {@code botToken} (use
 * {@code ${secret:TELEGRAM_TOKEN}}), optional {@code baseUrl}; inputs {@code chatId}, {@code text},
 * optional {@code replyTo}. Output {@code messageId}, {@code chatId}.
 *
 * <p>Poller {@code telegram} (getUpdates): config {@code botToken}, <b>{@code allowedChatIds} (required, non-empty)</b>,
 * optional {@code baseUrl}, {@code limit}. Anyone can message a bot, so updates from chats not on the list are dropped
 * (the cursor still advances past them) and never start a run. Each event: {@code updateId, chatId, chatType, fromId,
 * fromUsername, messageId, text, date}.
 */
public final class Telegram {

    private static final Pattern TOKEN = Pattern.compile("[0-9]{3,20}:[A-Za-z0-9_-]{20,100}");
    private static final int MAX_TEXT = 4096;
    private static final String DEFAULT_BASE = "https://api.telegram.org";

    private Telegram() {
    }

    private static Map<String, Object> api(HttpConnector http, Map<String, Object> config, String method, String httpMethod,
            Map<String, Object> body, Map<String, Object> query) throws Exception {
        Object token = config.get("botToken");
        if (!(token instanceof String t) || !TOKEN.matcher(t).matches()) {
            throw new ServiceHandler.ConnectorException("telegram needs a valid 'botToken' (use ${secret:NAME})", false);
        }
        String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b.replaceAll("/+$", "") : DEFAULT_BASE;
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", base + "/bot" + t + "/" + method);
        cfg.put("method", httpMethod);
        cfg.put("timeoutMs", 15_000);
        Map<String, Object> in = new LinkedHashMap<>();
        if (body != null) {
            in.put("body", body);
        }
        if (query != null) {
            Map<String, String> q = new LinkedHashMap<>();
            query.forEach((k, v) -> q.put(k, String.valueOf(v)));
            in.put("query", q);
        }
        Map<String, Object> res = http.call(cfg, in);
        if (!(res.get("body") instanceof Map<?, ?> m) || !Boolean.TRUE.equals(m.get("ok"))) {
            throw new ServiceHandler.ConnectorException("Telegram answered ok=false", false);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) m;
        return typed;
    }

    /** Connector {@code telegram}. */
    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("telegram needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String op = String.valueOf(config.getOrDefault("operation", "sendMessage"));
            if (!op.equals("sendMessage")) {
                throw new ServiceHandler.ConnectorException("Unsupported telegram operation '" + op + "'", false);
            }
            Object chat = inputs.get("chatId");
            Object text = inputs.get("text");
            if (!(chat instanceof Number) && !(chat instanceof String s && !s.isBlank())) {
                throw new ServiceHandler.ConnectorException("telegram sendMessage needs 'chatId'", false);
            }
            if (!(text instanceof String s) || s.isBlank()) {
                throw new ServiceHandler.ConnectorException("telegram sendMessage needs non-empty 'text'", false);
            }
            if (s.codePointCount(0, s.length()) > MAX_TEXT) {
                throw new ServiceHandler.ConnectorException("Text longer than " + MAX_TEXT + " characters", false);
            }
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("chat_id", chat);
            body.put("text", s);
            if (inputs.get("replyTo") instanceof Number n) {
                body.put("reply_to_message_id", n.longValue());
            }
            Map<String, Object> res = api(http, config, "sendMessage", "POST", body, null);
            Map<String, Object> out = new LinkedHashMap<>();
            if (res.get("result") instanceof Map<?, ?> r) {
                out.put("messageId", r.get("message_id"));
                if (r.get("chat") instanceof Map<?, ?> c) {
                    out.put("chatId", c.get("id"));
                }
            }
            return out;
        }
    }

    /** Poller {@code telegram}. */
    public static final class Updates implements Poller {
        private final HttpConnector http;

        public Updates(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Result poll(Map<String, Object> config, String cursor) throws Exception {
            Set<String> allowed = new java.util.HashSet<>();
            if (config.get("allowedChatIds") instanceof List<?> l) {
                l.forEach(o -> allowed.add(String.valueOf(o)));
            }
            if (allowed.isEmpty()) {
                throw new ServiceHandler.ConnectorException(
                        "telegram trigger needs a non-empty 'allowedChatIds': anyone can message a bot", false);
            }
            long limit = config.get("limit") instanceof Number n ? Math.max(1, Math.min(100, n.longValue())) : 50;
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("timeout", 0);
            q.put("limit", limit);
            q.put("allowed_updates", "[\"message\"]");
            if (cursor != null && !cursor.isBlank()) {
                q.put("offset", cursor);
            }
            Map<String, Object> res = api(http, config, "getUpdates", "GET", null, q);
            List<Map<String, Object>> events = new ArrayList<>();
            long max = -1;
            if (res.get("result") instanceof List<?> updates) {
                for (Object u : updates) {
                    if (!(u instanceof Map<?, ?> um) || !(um.get("update_id") instanceof Number id)) {
                        continue;
                    }
                    max = Math.max(max, id.longValue());
                    if (!(um.get("message") instanceof Map<?, ?> msg) || !(msg.get("chat") instanceof Map<?, ?> chat)) {
                        continue;
                    }
                    String chatId = String.valueOf(chat.get("id"));
                    if (!allowed.contains(chatId) || !(msg.get("text") instanceof String text)) {
                        continue; // not allowed, or not a text message
                    }
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("updateId", id.longValue());
                    e.put("chatId", chat.get("id"));
                    e.put("chatType", chat.get("type"));
                    if (msg.get("from") instanceof Map<?, ?> from) {
                        e.put("fromId", from.get("id"));
                        e.put("fromUsername", from.get("username"));
                    }
                    e.put("messageId", msg.get("message_id"));
                    e.put("text", text.length() > MAX_TEXT ? text.substring(0, MAX_TEXT) : text);
                    e.put("date", msg.get("date"));
                    events.add(e);
                }
            }
            return new Result(events, max < 0 ? null : Long.toString(max + 1));
        }
    }
}
