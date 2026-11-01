package tech.kayys.wayang.connectors;

import java.time.Instant;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.regex.Pattern;

import tech.kayys.wayang.runtime.ServiceHandler;
import tech.kayys.wayang.runtime.WebhookProvider;

/**
 * WhatsApp through the official <b>Cloud API</b> (Meta). Unofficial WhatsApp Web bridges are deliberately not supported:
 * they violate the terms of service and get numbers banned.
 *
 * <p>Webhook config: {@code appSecret} (signature), {@code verifyToken} (GET handshake), <b>{@code allowedNumbers}
 * (required, digits only, e.g. 628123456789)</b>. Event: {@code from, name, messageId, text, timestamp}. Only text messages.
 *
 * <p>Connector {@code whatsapp}: config {@code accessToken}, {@code phoneNumberId}, optional {@code baseUrl}, {@code apiVersion};
 * inputs {@code to}, {@code text}. Free-form text can only be sent within 24 hours of the user's last message; outside
 * that window Meta requires an approved template (not supported here).
 */
public final class WhatsApp {

    private static final Pattern DIGITS = Pattern.compile("[0-9]{5,20}");
    private static final Pattern VERSION = Pattern.compile("v[0-9]{1,2}\\.[0-9]");
    private static final int MAX_TEXT = 4096;
    private static final String DEFAULT_BASE = "https://graph.facebook.com";

    private WhatsApp() {
    }

    public static final class Webhook implements WebhookProvider {
        @Override
        public void verify(Map<String, Object> config, Map<String, String> headers, byte[] body, Instant now) throws Rejected {
            if (!(config.get("appSecret") instanceof String secret) || secret.isBlank()) {
                throw new Rejected("whatsapp webhook needs an appSecret");
            }
            String sig = headers.get("x-hub-signature-256");
            if (sig == null || !sig.startsWith("sha256=")) {
                throw new Rejected("missing signature");
            }
            if (!Hmac.same("sha256=" + Hmac.sha256Hex(secret, body), sig.trim())) {
                throw new Rejected("bad signature");
            }
        }

        @Override
        public Optional<String> handshake(Map<String, Object> config, Map<String, String> query, Map<String, Object> json) throws Rejected {
            if (query == null || !"subscribe".equals(query.get("hub.mode"))) {
                return Optional.empty();
            }
            if (!(config.get("verifyToken") instanceof String expected) || expected.isBlank()
                    || !Hmac.same(expected, query.get("hub.verify_token")) || query.get("hub.challenge") == null) {
                throw new Rejected("bad verify token");
            }
            return Optional.of(query.get("hub.challenge"));
        }

        @Override
        public List<Event> events(Map<String, Object> config, Map<String, Object> json) {
            Set<String> allowed = new HashSet<>();
            if (config.get("allowedNumbers") instanceof List<?> l) {
                l.forEach(o -> allowed.add(String.valueOf(o)));
            }
            List<Event> out = new ArrayList<>();
            if (allowed.isEmpty() || json == null || !(json.get("entry") instanceof List<?> entries)) {
                return out;
            }
            for (Object e : entries) {
                if (!(e instanceof Map<?, ?> em) || !(em.get("changes") instanceof List<?> changes)) {
                    continue;
                }
                for (Object c : changes) {
                    if (!(c instanceof Map<?, ?> cm) || !(cm.get("value") instanceof Map<?, ?> value)
                            || !(value.get("messages") instanceof List<?> messages)) {
                        continue;
                    }
                    Map<String, String> names = new LinkedHashMap<>();
                    if (value.get("contacts") instanceof List<?> contacts) {
                        for (Object ct : contacts) {
                            if (ct instanceof Map<?, ?> cn && cn.get("wa_id") != null && cn.get("profile") instanceof Map<?, ?> pr) {
                                names.put(String.valueOf(cn.get("wa_id")), String.valueOf(pr.get("name")));
                            }
                        }
                    }
                    for (Object m : messages) {
                        if (!(m instanceof Map<?, ?> mm) || !"text".equals(mm.get("type")) || !(mm.get("text") instanceof Map<?, ?> tm)
                                || !(tm.get("body") instanceof String body) || mm.get("id") == null) {
                            continue;
                        }
                        String from = String.valueOf(mm.get("from"));
                        if (!allowed.contains(from)) {
                            continue;
                        }
                        Map<String, Object> d = new LinkedHashMap<>();
                        d.put("from", from);
                        d.put("name", names.get(from));
                        d.put("messageId", mm.get("id"));
                        d.put("text", body.length() > MAX_TEXT ? body.substring(0, MAX_TEXT) : body);
                        d.put("timestamp", mm.get("timestamp"));
                        out.add(new Event(String.valueOf(mm.get("id")), d));
                    }
                }
            }
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
            throw new ServiceHandler.ConnectorException("whatsapp needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            if (!(config.get("accessToken") instanceof String token) || token.length() < 20) {
                throw new ServiceHandler.ConnectorException("whatsapp needs an 'accessToken' (use ${secret:NAME})", false);
            }
            if (!(config.get("phoneNumberId") instanceof String pid) || !DIGITS.matcher(pid).matches()) {
                throw new ServiceHandler.ConnectorException("whatsapp needs a numeric 'phoneNumberId'", false);
            }
            String version = config.get("apiVersion") instanceof String v ? v : "v21.0";
            if (!VERSION.matcher(version).matches()) {
                throw new ServiceHandler.ConnectorException("Invalid apiVersion", false);
            }
            if (!(inputs.get("to") instanceof String to) || !DIGITS.matcher(to).matches()) {
                throw new ServiceHandler.ConnectorException("whatsapp needs 'to' as digits only (country code, no +)", false);
            }
            if (!(inputs.get("text") instanceof String text) || text.isBlank()) {
                throw new ServiceHandler.ConnectorException("whatsapp needs non-empty 'text'", false);
            }
            if (text.length() > MAX_TEXT) {
                throw new ServiceHandler.ConnectorException("Text longer than " + MAX_TEXT + " characters", false);
            }
            String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b.replaceAll("/+$", "") : DEFAULT_BASE;
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("messaging_product", "whatsapp");
            body.put("to", to);
            body.put("type", "text");
            body.put("text", Map.of("body", text));
            Map<String, Object> cfg = new LinkedHashMap<>();
            cfg.put("url", base + "/" + version + "/" + pid + "/messages");
            cfg.put("method", "POST");
            cfg.put("headers", Map.of("Authorization", "Bearer " + token));
            Map<String, Object> res = http.call(cfg, Map.of("body", body));
            Map<String, Object> out = new LinkedHashMap<>();
            if (res.get("body") instanceof Map<?, ?> m && m.get("messages") instanceof List<?> l && !l.isEmpty()
                    && l.get(0) instanceof Map<?, ?> first) {
                out.put("messageId", first.get("id"));
            }
            return out;
        }
    }
}
