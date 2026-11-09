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
 * <p>Webhook config: {@code signingSecret} (use {@code ${secret:...}}), <b>{@code allowedChannels} (required)</b>, optional {@code allowedUsers} (only these senders count in an allowed channel). Requests
 * older or newer than 5 minutes are rejected (replay protection). Bot messages and message subtypes (edits, joins) are
 * dropped so the assistant cannot answer itself. Event: {@code type, channel, user, text, ts, threadTs}.
 *
 * <p>Connector {@code slack}: config {@code botToken} ({@code xoxb-...}), optional {@code baseUrl}; inputs {@code channel},
 * {@code text}, optional {@code threadTs}. Output {@code ts}, {@code channel}. Operation {@code uploadFile} shares a stored image (see {@link Sender}).
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
            if (config.get("allowedUsers") instanceof List<?> ul && !ul.isEmpty()
                    && ul.stream().noneMatch(u -> String.valueOf(u).equals(String.valueOf(ev.get("user"))))) {
                return out; // a channel lets every member write: with allowedUsers only these senders count
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
        private final MediaHttp raw;
        private final MediaStore media;

        public Sender(UrlGuard guard) {
            this(guard, MediaStore.fromEnvironment());
        }

        public Sender(UrlGuard guard, MediaStore media) {
            this.http = new HttpConnector(guard);
            this.raw = new MediaHttp(guard);
            this.media = media;
        }

        /**
         * {@code uploadFile}: Slack's external upload flow for a stored image: {@code files.getUploadURLExternal}, the bytes to the returned
         * URL (without the bot token: it is another host), then {@code files.completeUploadExternal} to share it in the channel. Needs the
         * {@code files:write} scope. {@code channel} must be a channel <b>id</b> (C..., G..., D...); config {@code channel} fixes it.
         * Inputs: {@code mediaId}, optional {@code text} (shown with the file), {@code threadTs}, {@code title}. Output: {@code fileId}, {@code channel}.
         */
        private Map<String, Object> upload(String token, String base, Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            Object ch = config.get("channel") != null ? config.get("channel") : inputs.get("channel");
            if (!(ch instanceof String channel) || !channel.matches("[CGD][A-Z0-9]{6,20}")) {
                throw new ServiceHandler.ConnectorException("slack uploadFile needs a channel id such as C0123456789", false);
            }
            String id = inputs.get("mediaId") instanceof String m ? m : null;
            byte[] bytes = id == null ? null : media.read(id);
            String mime = bytes == null ? null : MediaStore.sniff(bytes);
            if (mime == null) {
                throw new ServiceHandler.ConnectorException("slack uploadFile needs the 'mediaId' of a stored image", false);
            }
            String text = inputs.get("text") instanceof String t ? t : "";
            if (text.length() > 4000) {
                throw new ServiceHandler.ConnectorException("Text longer than 4000 characters", false);
            }
            String thread = inputs.get("threadTs") instanceof String t && !t.isBlank() ? t : null;
            if (thread != null && !thread.matches("[0-9]{1,12}\\.[0-9]{1,8}")) {
                throw new ServiceHandler.ConnectorException("slack threadTs is not valid", false);
            }
            String title = inputs.get("title") instanceof String t && !t.isBlank() ? t.replaceAll("\\p{Cntrl}", " ") : "image";
            if (title.length() > 200) {
                title = title.substring(0, 200);
            }
            String filename = "image." + (mime.equals("image/jpeg") ? "jpg" : mime.substring(6));
            Map<String, String> auth = Map.of("Authorization", "Bearer " + token);
            // 1. ask for an upload URL
            String form = "filename=" + java.net.URLEncoder.encode(filename, StandardCharsets.UTF_8) + "&length=" + bytes.length;
            Map<String, Object> first = slackOk(raw.postBytes(raw.uri(base, "/api/files.getUploadURLExternal"), auth, "application/x-www-form-urlencoded",
                    form.getBytes(StandardCharsets.UTF_8), 15_000, 1 << 20));
            if (!(first.get("upload_url") instanceof String uploadUrl) || !(first.get("file_id") instanceof String fileId)
                    || !fileId.matches("[A-Za-z0-9]{5,40}")) {
                throw new ServiceHandler.ConnectorException("Slack did not return an upload URL", false);
            }
            // 2. the bytes (no Authorization header: this is another host)
            raw.postRaw(raw.check(uploadUrl), mime, bytes, 60_000);
            // 3. share it
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("files", List.of(Map.of("id", fileId, "title", title)));
            body.put("channel_id", channel);
            if (!text.isBlank()) {
                body.put("initial_comment", text);
            }
            if (thread != null) {
                body.put("thread_ts", thread);
            }
            slackOk(raw.postJson(raw.uri(base, "/api/files.completeUploadExternal"), auth, body, 15_000, 1 << 20));
            return Map.of("fileId", fileId, "channel", channel);
        }

        @SuppressWarnings("unchecked")
        private static Map<String, Object> slackOk(Object res) throws ServiceHandler.ConnectorException {
            if (!(res instanceof Map<?, ?> m) || !Boolean.TRUE.equals(m.get("ok"))) {
                Object err = res instanceof Map<?, ?> m2 ? m2.get("error") : null;
                boolean retry = "ratelimited".equals(err) || "service_unavailable".equals(err) || "internal_error".equals(err);
                throw new ServiceHandler.ConnectorException("Slack error: " + (err == null ? "unknown" : String.valueOf(err).replaceAll("[^A-Za-z0-9_]", "?")), retry);
            }
            return (Map<String, Object>) m;
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
            String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b.replaceAll("/+$", "") : DEFAULT_BASE;
            String op = String.valueOf(config.getOrDefault("operation", "postMessage"));
            if (op.equals("uploadFile")) {
                return upload(token, base, config, inputs);
            }
            if (!op.equals("postMessage") && !op.equals("sendMessage")) {
                throw new ServiceHandler.ConnectorException("Unsupported slack operation '" + op + "'", false);
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
