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
 * <p>Connector {@code telegram} (operations {@code sendMessage}, {@code chatAction} (shows "typing" and the like; config or input {@code action}) and {@code sendPhoto}, the latter uploading a stored image by {@code mediaId}): config {@code botToken} (use
 * {@code ${secret:TELEGRAM_TOKEN}}), optional {@code baseUrl}; inputs {@code chatId}, {@code text},
 * optional {@code replyTo}. Output {@code messageId}, {@code chatId}.
 *
 * <p>Poller {@code telegram} (getUpdates): config {@code botToken}, <b>{@code allowedChatIds} (required, non-empty)</b>,
 * optional {@code allowedUserIds} (only these senders count inside an allowed chat: a group lets every member write), optional {@code baseUrl}, {@code limit}. Anyone can message a bot, so updates from chats not on the list are dropped
 * (the cursor still advances past them) and never start a run. Each event: {@code updateId, chatId, chatType, fromId,
 * fromUsername, messageId, text, date}; with {@code voice: true} a voice note of at most 5 minutes from an allowed chat is an event with an
 * empty {@code text} plus {@code voiceFileId} and {@code voiceSeconds} (fetch it with {@code downloadFile}); with {@code photo: true} a photo from an allowed chat is an event whose
 * {@code text} is the caption plus {@code photoFileId} (the largest size within {@link #MAX_PHOTO}; fetch it with {@code downloadFile} and {@code kind: image}).
 */
public final class Telegram {

    private static final Pattern TOKEN = Pattern.compile("[0-9]{3,20}:[A-Za-z0-9_-]{20,100}");
    private static final int MAX_TEXT = 4096;
    private static final String DEFAULT_BASE = "https://api.telegram.org";

    private Telegram() {
    }

    private static Map<String, Object> api(HttpConnector http, Map<String, Object> config, String method, String httpMethod,
            Map<String, Object> body, Map<String, Object> query) throws Exception {
        return api(http, config, method, httpMethod, body, query, null);
    }

    private static Map<String, Object> api(HttpConnector http, Map<String, Object> config, String method, String httpMethod,
            Object body, Map<String, Object> query, Map<String, String> headers) throws Exception {
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
        if (headers != null) {
            in.put("headers", headers);
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
        private final MediaStore media;
        private final MediaHttp raw;

        public Sender(UrlGuard guard) {
            this(guard, MediaStore.fromEnvironment());
        }

        public Sender(UrlGuard guard, MediaStore media) {
            this.http = new HttpConnector(guard);
            this.raw = new MediaHttp(guard);
            this.media = media;
        }

        /**
         * {@code downloadFile}: fetches a voice message or audio file by {@code fileId} (Telegram's own limit is 20 MB; here at most
         * {@link #MAX_AUDIO}) into the media store and returns {@code mediaId}. The chat is not checked here: a poller only emits files from
         * allowed chats, and a file id is unguessable.
         */
        private Map<String, Object> download(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String fileId = inputs.get("fileId") instanceof String f ? f : null;
            if (fileId == null || !fileId.matches("[A-Za-z0-9_-]{10,200}")) {
                throw new ServiceHandler.ConnectorException("telegram downloadFile needs a valid 'fileId'", false);
            }
            boolean image = "image".equals(inputs.get("kind"));
            if (inputs.get("kind") != null && !image && !"audio".equals(inputs.get("kind"))) {
                throw new ServiceHandler.ConnectorException("telegram downloadFile 'kind' is audio or image", false);
            }
            long limit = image ? MAX_PHOTO : MAX_AUDIO;
            Map<String, Object> info = api(http, config, "getFile", "GET", null, Map.of("file_id", fileId));
            String path = info.get("result") instanceof Map<?, ?> r && r.get("file_path") instanceof String p ? p : null;
            if (path == null || !path.matches("[A-Za-z0-9_./-]{1,200}") || path.contains("..") || path.startsWith("/")) {
                throw new ServiceHandler.ConnectorException("Telegram returned no usable file path", false);
            }
            if (info.get("result") instanceof Map<?, ?> r && r.get("file_size") instanceof Number n && n.longValue() > limit) {
                throw new ServiceHandler.ConnectorException("The " + (image ? "image" : "audio") + " is larger than " + (limit >> 20) + " MiB", false);
            }
            String base = config.get("baseUrl") instanceof String b && !b.isBlank() ? b.replaceAll("/+$", "") : DEFAULT_BASE;
            byte[] bytes = raw.getBytes(raw.uri(base, "/file/bot" + config.get("botToken") + "/" + path), Map.of(), 60_000, limit);
            return image ? media.save(bytes) : media.saveAudio(bytes);
        }

        private Map<String, Object> sendPhoto(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            return photo(http, media, config, inputs);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("telegram needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            String op = String.valueOf(config.getOrDefault("operation", "sendMessage"));
            if (op.equals("sendPhoto")) {
                return sendPhoto(config, inputs);
            }
            if (op.equals("sendVoice")) {
                return voice(http, media, config, inputs);
            }
            if (op.equals("downloadFile")) {
                return download(config, inputs);
            }
            if (op.equals("chatAction")) {
                Object chat = config.get("chatId") != null ? config.get("chatId") : inputs.get("chatId");
                String action = String.valueOf(config.getOrDefault("action", inputs.getOrDefault("action", "typing")));
                if (!(chat instanceof Number) && !(chat instanceof String c && !c.isBlank())) {
                    throw new ServiceHandler.ConnectorException("telegram chatAction needs 'chatId'", false);
                }
                if (!CHAT_ACTIONS.contains(action)) {
                    throw new ServiceHandler.ConnectorException("telegram chatAction 'action' is one of " + CHAT_ACTIONS, false);
                }
                api(http, config, "sendChatAction", "POST", Map.of("chat_id", chat, "action", action), null);
                return Map.of("action", action);
            }
            if (!op.equals("sendMessage")) {
                throw new ServiceHandler.ConnectorException("Unsupported telegram operation '" + op + "'", false);
            }
            Object chat = config.get("chatId") != null ? config.get("chatId") : inputs.get("chatId");
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
            if (Boolean.TRUE.equals(inputs.get("rate")) || "true".equals(inputs.get("rate"))) {
                // thumbs under the reply; a press arrives as a callback_query that the poller (with ratings:true) turns into a feedback event
                body.put("reply_markup", Map.of("inline_keyboard", List.of(List.of(
                        Map.of("text", "\uD83D\uDC4D", "callback_data", RATE_UP), Map.of("text", "\uD83D\uDC4E", "callback_data", RATE_DOWN)))));
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

    static final String RATE_UP = "fb:up";
    static final String RATE_DOWN = "fb:down";
    /** How much of the rated reply goes into the feedback event. */
    static final int RATED_EXCERPT = 200;

    private static final int MAX_CAPTION = 1024;
    static final long MAX_AUDIO = 10L << 20;
    static final long MAX_PHOTO = 5L << 20;
    static final java.util.Set<String> CHAT_ACTIONS = new java.util.TreeSet<>(java.util.List.of("typing", "upload_photo", "record_voice", "upload_voice"));
    static final long MAX_VOICE_SECONDS = 300;

    /**
     * {@code sendPhoto}: uploads a stored image ({@code mediaId} from the {@link MediaStore}) with an optional {@code caption}. The chat is
     * {@code chatId} from the configuration when set (a tool the model may call should fix it), else from the inputs.
     */
    private static Map<String, Object> photo(HttpConnector http, MediaStore media, Map<String, Object> config, Map<String, Object> inputs)
            throws Exception {
        Object chat = config.get("chatId") != null ? config.get("chatId") : inputs.get("chatId");
        if (!(chat instanceof Number) && !(chat instanceof String cs && !cs.isBlank())) {
            throw new ServiceHandler.ConnectorException("telegram sendPhoto needs 'chatId'", false);
        }
        String chatText = String.valueOf(chat);
        if (!chatText.matches("-?[0-9]{1,20}|@[A-Za-z0-9_]{4,64}")) {
            throw new ServiceHandler.ConnectorException("telegram chatId is not valid", false);
        }
        String id = inputs.get("mediaId") instanceof String m ? m : null;
        byte[] bytes = id == null ? null : media.read(id);
        if (bytes == null) {
            throw new ServiceHandler.ConnectorException("telegram sendPhoto needs a 'mediaId' of a stored image", false);
        }
        String caption = inputs.get("caption") instanceof String c ? c : "";
        if (caption.codePointCount(0, caption.length()) > MAX_CAPTION) {
            throw new ServiceHandler.ConnectorException("Caption longer than " + MAX_CAPTION + " characters", false);
        }
        String mime = MediaStore.sniff(bytes);
        if (mime == null) {
            throw new ServiceHandler.ConnectorException("telegram sendPhoto: the mediaId is not an image", false);
        }
        String boundary = "wayang" + java.util.UUID.randomUUID().toString().replace("-", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        field(out, boundary, "chat_id", chatText);
        if (!caption.isEmpty()) {
            field(out, boundary, "caption", caption);
        }
        if (inputs.get("replyTo") instanceof Number n) {
            field(out, boundary, "reply_to_message_id", Long.toString(n.longValue()));
        }
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"photo\"; filename=\"image." + (mime.equals("image/jpeg") ? "jpg" : mime.substring(6))
                + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> res = api(http, config, "sendPhoto", "POST", out.toByteArray(), null, Map.of("Content-Type", "multipart/form-data; boundary=" + boundary));
        Map<String, Object> result = new LinkedHashMap<>();
        if (res.get("result") instanceof Map<?, ?> r) {
            result.put("messageId", r.get("message_id"));
        }
        return result;
    }

    /**
     * {@code sendVoice}: sends stored audio ({@code mediaId}) as a voice note when it is Ogg/Opus, otherwise as an audio file. The chat is
     * {@code chatId} from the configuration when set, else from the inputs.
     */
    private static Map<String, Object> voice(HttpConnector http, MediaStore media, Map<String, Object> config, Map<String, Object> inputs)
            throws Exception {
        Object chat = config.get("chatId") != null ? config.get("chatId") : inputs.get("chatId");
        String chatText = chat instanceof Number || chat instanceof String cs && !cs.isBlank() ? String.valueOf(chat) : "";
        if (!chatText.matches("-?[0-9]{1,20}|@[A-Za-z0-9_]{4,64}")) {
            throw new ServiceHandler.ConnectorException("telegram sendVoice needs a valid 'chatId'", false);
        }
        String id = inputs.get("mediaId") instanceof String m ? m : null;
        byte[] bytes = id == null ? null : media.read(id);
        String mime = bytes == null ? null : MediaStore.sniffAudio(bytes);
        if (mime == null) {
            throw new ServiceHandler.ConnectorException("telegram sendVoice needs a 'mediaId' of stored audio", false);
        }
        boolean note = mime.equals("audio/ogg");
        String boundary = "wayang" + java.util.UUID.randomUUID().toString().replace("-", "");
        java.io.ByteArrayOutputStream out = new java.io.ByteArrayOutputStream();
        field(out, boundary, "chat_id", chatText);
        if (inputs.get("replyTo") instanceof Number n) {
            field(out, boundary, "reply_to_message_id", Long.toString(n.longValue()));
        }
        String ext = id.substring(id.lastIndexOf('.') + 1);
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + (note ? "voice" : "audio") + "\"; filename=\"audio." + ext
                + "\"\r\nContent-Type: " + mime + "\r\n\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        out.write(bytes);
        out.write(("\r\n--" + boundary + "--\r\n").getBytes(java.nio.charset.StandardCharsets.UTF_8));
        Map<String, Object> res = api(http, config, note ? "sendVoice" : "sendAudio", "POST", out.toByteArray(), null,
                Map.of("Content-Type", "multipart/form-data; boundary=" + boundary));
        Map<String, Object> result = new LinkedHashMap<>();
        if (res.get("result") instanceof Map<?, ?> r) {
            result.put("messageId", r.get("message_id"));
        }
        return result;
    }

    private static void field(java.io.ByteArrayOutputStream out, String boundary, String name, String value) throws java.io.IOException {
        out.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"" + name + "\"\r\n\r\n" + value + "\r\n")
                .getBytes(java.nio.charset.StandardCharsets.UTF_8));
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
            Set<String> users = new java.util.HashSet<>();
            if (config.get("allowedUserIds") instanceof List<?> ul) {
                ul.forEach(o -> users.add(String.valueOf(o)));
            }
            if (allowed.isEmpty()) {
                throw new ServiceHandler.ConnectorException(
                        "telegram trigger needs a non-empty 'allowedChatIds': anyone can message a bot", false);
            }
            long limit = config.get("limit") instanceof Number n ? Math.max(1, Math.min(100, n.longValue())) : 50;
            Map<String, Object> q = new LinkedHashMap<>();
            q.put("timeout", 0);
            q.put("limit", limit);
            boolean ratings = Boolean.TRUE.equals(config.get("ratings"));
            q.put("allowed_updates", ratings ? "[\"message\",\"callback_query\"]" : "[\"message\"]");
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
                    if (ratings && um.get("callback_query") instanceof Map<?, ?> cb) {
                        Map<String, Object> fb = feedbackEvent(http, config, id.longValue(), cb, allowed, users);
                        if (fb != null) {
                            events.add(fb);
                        }
                        continue;
                    }
                    if (!(um.get("message") instanceof Map<?, ?> msg) || !(msg.get("chat") instanceof Map<?, ?> chat)) {
                        continue;
                    }
                    String chatId = String.valueOf(chat.get("id"));
                    if (!allowed.contains(chatId)) {
                        continue;
                    }
                    // In a group every member can write: with allowedUserIds only these senders count (a message with no sender is dropped).
                    if (!users.isEmpty() && !(msg.get("from") instanceof Map<?, ?> sender && users.contains(String.valueOf(sender.get("id"))))) {
                        continue;
                    }
                    String voiceId = null;
                    long voiceSeconds = 0;
                    if (!(msg.get("text") instanceof String) && Boolean.TRUE.equals(config.get("voice"))
                            && msg.get("voice") instanceof Map<?, ?> v && v.get("file_id") instanceof String fid && fid.matches("[A-Za-z0-9_-]{10,200}")
                            && v.get("duration") instanceof Number dur && dur.longValue() <= MAX_VOICE_SECONDS) {
                        voiceId = fid;
                        voiceSeconds = dur.longValue();
                    }
                    String photoId = null;
                    if (voiceId == null && !(msg.get("text") instanceof String) && Boolean.TRUE.equals(config.get("photo"))
                            && msg.get("photo") instanceof List<?> sizes) {
                        photoId = largestPhoto(sizes);
                    }
                    if (voiceId == null && photoId == null && !(msg.get("text") instanceof String)) {
                        continue; // not a text message (and no accepted voice note or photo)
                    }
                    String text = msg.get("text") instanceof String tx ? tx : photoId != null && msg.get("caption") instanceof String cap ? cap : "";
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
                    if (voiceId != null) {
                        e.put("voiceFileId", voiceId);
                        e.put("voiceSeconds", voiceSeconds);
                    }
                    if (photoId != null) {
                        e.put("photoFileId", photoId);
                    }
                    e.put("date", msg.get("date"));
                    events.add(e);
                }
            }
            return new Result(events, max < 0 ? null : Long.toString(max + 1));
        }
    }

    /**
     * A thumbs press on one of the bot's replies, as an event {@code {kind: "feedback", rating, chatId, fromId, messageId, reply}}; null for
     * anything else (another button, a chat or person that is not allowed). The press is acknowledged so the button stops spinning; if that
     * fails the event still counts.
     */
    private static Map<String, Object> feedbackEvent(HttpConnector http, Map<String, Object> config, long updateId, Map<?, ?> cb, Set<String> allowed,
            Set<String> users) {
        String data = cb.get("data") instanceof String d ? d : "";
        if (!data.equals(RATE_UP) && !data.equals(RATE_DOWN)) {
            return null;
        }
        if (!(cb.get("message") instanceof Map<?, ?> msg) || !(msg.get("chat") instanceof Map<?, ?> chat) || !allowed.contains(String.valueOf(chat.get("id")))) {
            return null;
        }
        Object from = cb.get("from") instanceof Map<?, ?> f ? f.get("id") : null;
        if (!users.isEmpty() && (from == null || !users.contains(String.valueOf(from)))) {
            return null;
        }
        if (cb.get("id") instanceof String qid && qid.matches("[A-Za-z0-9_-]{1,100}")) {
            try {
                api(http, config, "answerCallbackQuery", "POST", Map.of("callback_query_id", qid, "text", "Thanks for the feedback"), null);
            } catch (Exception ignored) {
                // only the spinner: the rating itself is still recorded
            }
        }
        String reply = msg.get("text") instanceof String t ? t : "";
        Map<String, Object> e = new LinkedHashMap<>();
        e.put("kind", "feedback");
        e.put("updateId", updateId);
        e.put("rating", data.equals(RATE_UP) ? "up" : "down");
        e.put("chatId", chat.get("id"));
        if (from != null) {
            e.put("fromId", from);
        }
        e.put("messageId", msg.get("message_id"));
        e.put("reply", reply.length() > RATED_EXCERPT ? reply.substring(0, RATED_EXCERPT) : reply);
        return e;
    }

    /** The file id of the largest photo size within {@link #MAX_PHOTO} (Telegram sends several sizes of one picture), or null. */
    static String largestPhoto(List<?> sizes) {
        String best = null;
        long bestPixels = -1;
        for (Object o : sizes) {
            if (o instanceof Map<?, ?> m && m.get("file_id") instanceof String id && id.matches("[A-Za-z0-9_-]{10,200}")
                    && m.get("width") instanceof Number w && m.get("height") instanceof Number h) {
                long size = m.get("file_size") instanceof Number n ? n.longValue() : 0;
                long pixels = w.longValue() * h.longValue();
                if (size <= MAX_PHOTO && pixels > bestPixels) {
                    best = id;
                    bestPixels = pixels;
                }
            }
        }
        return best;
    }
}
