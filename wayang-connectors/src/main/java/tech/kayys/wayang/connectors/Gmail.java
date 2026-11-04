package tech.kayys.wayang.connectors;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Gmail REST API over {@link HttpConnector}. {@code accessToken} comes from an OAuth connection:
 * {@code ${secret:oauth.<connection>}}.
 *
 * <p>Poller {@code gmail}: config {@code accessToken}; <b>{@code allowedSenders} (required, non-empty) unless
 * {@code acceptAnySender: true}</b>; optional {@code query} (default {@code in:inbox}), {@code maxResults} (1..50),
 * {@code includeBody} (default false: headers and snippet only), {@code requireAuthenticated} (default true: the first
 * {@code Authentication-Results} header, added by Gmail's own MX, must show dkim=pass or spf=pass, because a From
 * header alone is trivially forged), {@code baseUrl}. First poll records "now" and returns nothing (no backfill of the
 * mailbox). Events: {@code messageId, threadId, from, subject, snippet, date (epoch ms), rfcMessageId, body?}.
 *
 * <p>Connector {@code gmail} operations {@code search} (inputs {@code q}, {@code max}) and {@code read} (input {@code messageId}) are read-only.
 * Operation {@code send}: config {@code accessToken}, optional {@code allowedRecipients}
 * (addresses; when set every recipient must be on it), {@code baseUrl}; inputs {@code to}, {@code subject},
 * {@code text}, optional {@code threadId}, {@code inReplyTo}. Header injection (CR/LF) is refused.
 */
public final class Gmail {

    private static final String DEFAULT_BASE = "https://gmail.googleapis.com";
    private static final Pattern EMAIL = Pattern.compile("[A-Za-z0-9._%+'-]{1,64}@[A-Za-z0-9.-]{1,255}\\.[A-Za-z]{2,24}");
    private static final Pattern ANGLE = Pattern.compile("<([^<>\\s]+)>\\s*$");
    private static final Pattern ID = Pattern.compile("[A-Za-z0-9_-]{1,64}");
    private static final int MAX_BODY_IN = 8000;
    private static final int MAX_TEXT_OUT = 100_000;

    private Gmail() {
    }

    /** Lower-cased address of a From header ({@code Name <a@b>} or bare), or null when it is not a plain address. */
    static String address(String from) {
        if (from == null) {
            return null;
        }
        String s = from.trim();
        Matcher m = ANGLE.matcher(s);
        if (m.find()) {
            s = m.group(1);
        }
        return EMAIL.matcher(s).matches() ? s.toLowerCase(Locale.ROOT) : null;
    }

    private static String token(Map<String, Object> config) throws ServiceHandler.ConnectorException {
        if (!(config.get("accessToken") instanceof String t) || t.isBlank() || t.length() > 4096 || t.matches(".*\\s.*")) {
            throw new ServiceHandler.ConnectorException("gmail needs 'accessToken' (use ${secret:oauth.<connection>})", false);
        }
        return t;
    }

    private static Map<String, Object> call(HttpConnector http, Map<String, Object> config, String method, String path,
            Map<String, Object> query, Object body) throws Exception {
        String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b.replaceAll("/+$", "") : DEFAULT_BASE;
        Map<String, Object> cfg = new LinkedHashMap<>();
        cfg.put("url", base + path);
        cfg.put("method", method);
        cfg.put("timeoutMs", 20_000);
        cfg.put("headers", Map.of("Authorization", "Bearer " + token(config)));
        Map<String, Object> in = new LinkedHashMap<>();
        if (query != null) {
            in.put("query", query);
        }
        if (body != null) {
            in.put("body", body);
        }
        Map<String, Object> res = http.call(cfg, in);
        if (!(res.get("body") instanceof Map<?, ?> m)) {
            throw new ServiceHandler.ConnectorException("Gmail answered with something other than JSON", false);
        }
        @SuppressWarnings("unchecked")
        Map<String, Object> typed = (Map<String, Object>) m;
        return typed;
    }

    private static Set<String> addresses(Object v) {
        Set<String> out = new java.util.HashSet<>();
        if (v instanceof List<?> l) {
            for (Object o : l) {
                String a = address(String.valueOf(o));
                if (a != null) {
                    out.add(a);
                }
            }
        }
        return out;
    }

    // --------------------------------------------------------------- poller

    public static final class Inbox implements Poller {
        private final HttpConnector http;

        public Inbox(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Result poll(Map<String, Object> config, String cursor) throws Exception {
            boolean any = Boolean.TRUE.equals(config.get("acceptAnySender"));
            Set<String> allowed = addresses(config.get("allowedSenders"));
            if (allowed.isEmpty() && !any) {
                throw new ServiceHandler.ConnectorException(
                        "gmail trigger needs a non-empty 'allowedSenders' (or acceptAnySender: true): anyone can email you", false);
            }
            boolean requireAuth = !Boolean.FALSE.equals(config.get("requireAuthenticated"));
            boolean body = Boolean.TRUE.equals(config.get("includeBody"));
            long max = config.get("maxResults") instanceof Number n ? Math.max(1, Math.min(50, n.longValue())) : 25;
            String query = config.get("query") instanceof String q && !q.isBlank() ? q : "in:inbox";
            token(config);
            if (cursor == null || cursor.isBlank()) {
                return new Result(List.of(), Long.toString(System.currentTimeMillis()));
            }
            long since;
            try {
                since = Long.parseLong(cursor);
            } catch (NumberFormatException e) {
                throw new ServiceHandler.ConnectorException("Corrupt cursor", false);
            }
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("q", query + " after:" + Math.max(0, since / 1000 - 1));
            q.put("maxResults", max);
            Map<String, Object> list = call(http, config, "GET", "/gmail/v1/users/me/messages", q, null);
            List<Map<String, Object>> events = new ArrayList<>();
            long newest = since;
            if (list.get("messages") instanceof List<?> ms) {
                for (Object o : ms) {
                    if (!(o instanceof Map<?, ?> mm) || !(mm.get("id") instanceof String id) || !ID.matcher(id).matches()) {
                        continue;
                    }
                    Map<String, Object> msg = fetch(config, id, body);
                    long date = msg.get("internalDate") instanceof String d ? parse(d) : 0;
                    if (date <= since) {
                        continue;
                    }
                    newest = Math.max(newest, date);
                    Map<String, String> h = headers(msg);
                    String from = address(h.get("from"));
                    if (from == null || (!any && !allowed.contains(from))) {
                        continue;
                    }
                    if (requireAuth && !authenticated(h.get("authentication-results"))) {
                        continue;
                    }
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("messageId", id);
                    e.put("threadId", msg.get("threadId"));
                    e.put("from", from);
                    e.put("subject", clip(h.getOrDefault("subject", ""), 500));
                    e.put("snippet", clip(String.valueOf(msg.getOrDefault("snippet", "")), 500));
                    e.put("date", date);
                    e.put("rfcMessageId", h.get("message-id"));
                    if (body) {
                        e.put("body", clip(plainText(msg.get("payload")), MAX_BODY_IN));
                    }
                    events.add(e);
                }
            }
            events.sort((a, b) -> Long.compare((Long) a.get("date"), (Long) b.get("date")));
            return new Result(events, Long.toString(newest));
        }

        private Map<String, Object> fetch(Map<String, Object> config, String id, boolean body) throws Exception {
            Map<String, Object> q = new LinkedHashMap<>();
            if (body) {
                q.put("format", "full");
            } else {
                q.put("format", "metadata");
            }
            // format=metadata returns every header, so Subject, Message-ID and Authentication-Results are picked client-side.
            return call(http, config, "GET", "/gmail/v1/users/me/messages/" + id, q, null);
        }
    }

    private static long parse(String s) {
        try {
            return Long.parseLong(s);
        } catch (NumberFormatException e) {
            return 0;
        }
    }

    private static String clip(String s, int n) {
        return s.length() > n ? s.substring(0, n) : s;
    }

    /** First occurrence of each header, names lower-cased. The first Authentication-Results is Gmail's own. */
    private static Map<String, String> headers(Map<String, Object> msg) {
        Map<String, String> out = new LinkedHashMap<>();
        if (msg.get("payload") instanceof Map<?, ?> p && p.get("headers") instanceof List<?> hs) {
            for (Object o : hs) {
                if (o instanceof Map<?, ?> h && h.get("name") instanceof String n && h.get("value") instanceof String v) {
                    out.putIfAbsent(n.toLowerCase(Locale.ROOT), v);
                }
            }
        }
        return out;
    }

    private static boolean authenticated(String results) {
        if (results == null) {
            return false;
        }
        String r = results.toLowerCase(Locale.ROOT);
        return r.contains("dkim=pass") || r.contains("spf=pass");
    }

    private static String plainText(Object payload) {
        if (!(payload instanceof Map<?, ?> p)) {
            return "";
        }
        if ("text/plain".equals(p.get("mimeType")) && p.get("body") instanceof Map<?, ?> b && b.get("data") instanceof String d) {
            try {
                return new String(Base64.getUrlDecoder().decode(d), StandardCharsets.UTF_8);
            } catch (IllegalArgumentException e) {
                return "";
            }
        }
        if (p.get("parts") instanceof List<?> parts) {
            for (Object o : parts) {
                String t = plainText(o);
                if (!t.isEmpty()) {
                    return t;
                }
            }
        }
        return "";
    }

    // --------------------------------------------------------------- sender

    public static final class Sender implements ServiceHandler.Connector {
        private final HttpConnector http;

        public Sender(UrlGuard guard) {
            this.http = new HttpConnector(guard);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("gmail needs node configuration", false);
        }

        /** Read-only: {@code q} (Gmail search syntax, up to 200 chars) and {@code max} (1..10); returns sender, subject, date and snippet only. */
        private Map<String, Object> search(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String q = str(inputs.get("q"));
            if (q != null && (q.length() > 200 || crlf(q))) {
                throw new ServiceHandler.ConnectorException("gmail search 'q' must be one line of at most 200 characters", false);
            }
            long max = inputs.get("max") instanceof Number n ? Math.max(1, Math.min(10, n.longValue())) : 5;
            Map<String, Object> query = new LinkedHashMap<>();
            query.put("q", q == null ? "in:inbox" : q);
            query.put("maxResults", max);
            Map<String, Object> list = Gmail.call(http, config, "GET", "/gmail/v1/users/me/messages", query, null);
            List<Map<String, Object>> out = new ArrayList<>();
            if (list.get("messages") instanceof List<?> ms) {
                for (Object o : ms) {
                    if (out.size() >= max || !(o instanceof Map<?, ?> mm) || !(mm.get("id") instanceof String id) || !ID.matcher(id).matches()) {
                        continue;
                    }
                    Map<String, Object> msg = Gmail.call(http, config, "GET", "/gmail/v1/users/me/messages/" + id, Map.<String, Object>of("format", "metadata"), null);
                    out.add(summary(id, msg, null));
                }
            }
            return Map.of("messages", out);
        }

        /** Read-only: one message by id, body clipped to 8000 characters. Mail is untrusted content. */
        private Map<String, Object> read(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String id = str(inputs.get("messageId"));
            if (id == null || !ID.matcher(id).matches()) {
                throw new ServiceHandler.ConnectorException("gmail read needs a valid 'messageId'", false);
            }
            Map<String, Object> msg = Gmail.call(http, config, "GET", "/gmail/v1/users/me/messages/" + id, Map.<String, Object>of("format", "full"), null);
            return summary(id, msg, clip(plainText(msg.get("payload")), MAX_BODY_IN));
        }

        private static Map<String, Object> summary(String id, Map<String, Object> msg, String body) {
            Map<String, String> h = headers(msg);
            Map<String, Object> e = new LinkedHashMap<>();
            e.put("messageId", id);
            e.put("from", clip(h.getOrDefault("from", ""), 300));
            e.put("subject", clip(h.getOrDefault("subject", ""), 500));
            e.put("date", msg.get("internalDate") instanceof String d ? parse(d) : 0);
            e.put("authenticated", authenticated(h.get("authentication-results")));
            if (body == null) {
                e.put("snippet", clip(String.valueOf(msg.getOrDefault("snippet", "")), 500));
            } else {
                e.put("body", body);
            }
            return e;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String op = String.valueOf(config.getOrDefault("operation", "send"));
            if (op.equals("search")) {
                return search(config, inputs);
            }
            if (op.equals("read")) {
                return read(config, inputs);
            }
            if (!op.equals("send")) {
                throw new ServiceHandler.ConnectorException("Unsupported gmail operation '" + op + "'", false);
            }
            String to = str(inputs.get("to"));
            String subject = str(inputs.get("subject"));
            String text = inputs.get("text") instanceof String s ? s : null;
            if (to == null || !EMAIL.matcher(to).matches()) {
                throw new ServiceHandler.ConnectorException("gmail send needs a single valid 'to' address", false);
            }
            if (config.get("allowedRecipients") != null && !addresses(config.get("allowedRecipients")).contains(to.toLowerCase(Locale.ROOT))) {
                throw new ServiceHandler.ConnectorException("Recipient is not on allowedRecipients", false);
            }
            if (subject == null || subject.isBlank() || subject.length() > 300 || crlf(subject)) {
                throw new ServiceHandler.ConnectorException("gmail send needs a 'subject' of 1..300 characters on one line", false);
            }
            if (text == null || text.isBlank() || text.length() > MAX_TEXT_OUT) {
                throw new ServiceHandler.ConnectorException("gmail send needs 'text' of 1.." + MAX_TEXT_OUT + " characters", false);
            }
            String inReplyTo = str(inputs.get("inReplyTo"));
            if (inReplyTo != null && (crlf(inReplyTo) || inReplyTo.length() > 300 || !inReplyTo.matches("<[^<>\\s]+>"))) {
                throw new ServiceHandler.ConnectorException("inReplyTo must be a Message-ID like <id@host>", false);
            }
            String thread = str(inputs.get("threadId"));
            if (thread != null && !ID.matcher(thread).matches()) {
                throw new ServiceHandler.ConnectorException("threadId is invalid", false);
            }
            StringBuilder m = new StringBuilder();
            m.append("To: ").append(to).append("\r\n");
            m.append("Subject: ").append(subject.chars().allMatch(c -> c < 128 && c >= 32) ? subject
                    : "=?UTF-8?B?" + Base64.getEncoder().encodeToString(subject.getBytes(StandardCharsets.UTF_8)) + "?=").append("\r\n");
            if (inReplyTo != null) {
                m.append("In-Reply-To: ").append(inReplyTo).append("\r\n");
                m.append("References: ").append(inReplyTo).append("\r\n");
            }
            m.append("MIME-Version: 1.0\r\nContent-Type: text/plain; charset=UTF-8\r\nContent-Transfer-Encoding: base64\r\n\r\n");
            m.append(Base64.getMimeEncoder(76, "\r\n".getBytes(StandardCharsets.US_ASCII))
                    .encodeToString(text.getBytes(StandardCharsets.UTF_8)));
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("raw", Base64.getUrlEncoder().withoutPadding().encodeToString(m.toString().getBytes(StandardCharsets.US_ASCII)));
            if (thread != null) {
                body.put("threadId", thread);
            }
            Map<String, Object> res = Gmail.call(http, config, "POST", "/gmail/v1/users/me/messages/send", null, body);
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("messageId", res.get("id"));
            out.put("threadId", res.get("threadId"));
            return out;
        }

        private static String str(Object o) {
            return o instanceof String s && !s.isBlank() ? s.trim() : null;
        }

        private static boolean crlf(String s) {
            return s.indexOf('\r') >= 0 || s.indexOf('\n') >= 0 || s.indexOf('\0') >= 0;
        }
    }
}
