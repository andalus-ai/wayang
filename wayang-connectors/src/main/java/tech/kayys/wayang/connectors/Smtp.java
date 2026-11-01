package tech.kayys.wayang.connectors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZoneOffset;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.UUID;

import javax.net.ssl.SSLSocketFactory;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Connector {@code smtp}: sends plain-text and/or HTML mail through any SMTP server (not tied to Google).
 *
 * <p>Config: {@code host}, {@code port} (default by security), {@code security} {@code tls} | {@code starttls} | {@code none}
 * (default: tls on 465, else starttls), {@code username}, {@code password} (use {@code ${secret:NAME}}), {@code from}, optional
 * {@code fromName}, {@code heloName}. Inputs: {@code to} (string or list), {@code cc}, {@code bcc}, {@code subject},
 * {@code text} and/or {@code html}, optional {@code replyTo}, {@code inReplyTo}. Output: {@code messageId}, {@code recipients}.
 *
 * <p>Safety: credentials are only ever sent over TLS (STARTTLS is required to be advertised, never silently skipped);
 * certificates and host names are verified; no header value may contain control characters; at most {@value #MAX_RCPT} recipients;
 * body at most {@value #MAX_BODY} characters.
 */
public final class Smtp {

    static final int MAX_RCPT = 50;
    static final int MAX_BODY = 1_000_000;

    private Smtp() {
    }

    public static final class Sender implements ServiceHandler.Connector {
        private final UrlGuard guard;
        private final SSLSocketFactory ssl;

        public Sender(UrlGuard guard) {
            this(guard, (SSLSocketFactory) SSLSocketFactory.getDefault());
        }

        Sender(UrlGuard guard, SSLSocketFactory ssl) {
            this.guard = guard;
            this.ssl = ssl;
        }

        @Override
        public Map<String, Object> call(Map<String, Object> inputs) throws Exception {
            throw new ServiceHandler.ConnectorException("smtp needs node configuration", false);
        }

        @Override
        public Map<String, Object> call(Map<String, Object> config, Map<String, Object> inputs) throws Exception {
            if (!(config.get("host") instanceof String host) || host.isBlank()) {
                throw new ServiceHandler.ConnectorException("smtp needs a 'host'", false);
            }
            int port = config.get("port") instanceof Number n ? n.intValue() : 0;
            MailWire.Security sec = MailWire.Security.of(config.get("security"), port == 0 ? 587 : port);
            if (port == 0) {
                port = sec == MailWire.Security.TLS ? 465 : sec == MailWire.Security.STARTTLS ? 587 : 25;
            }
            String user = config.get("username") instanceof String u && !u.isEmpty() ? MailWire.clean(u, "username") : null;
            String pass = config.get("password") instanceof String p && !p.isEmpty() ? MailWire.clean(p, "password") : null;
            if ((user == null) != (pass == null)) {
                throw new ServiceHandler.ConnectorException("smtp needs both 'username' and 'password', or neither", false);
            }
            if (user != null && sec == MailWire.Security.NONE) {
                throw new ServiceHandler.ConnectorException("Refusing to send credentials without TLS (security: none)", false);
            }
            String from = Gmail.address(config.get("from") instanceof String f ? MailWire.clean(f, "from") : null);
            if (from == null) {
                throw new ServiceHandler.ConnectorException("smtp needs a valid 'from' address", false);
            }
            String fromName = config.get("fromName") instanceof String fn && !fn.isBlank() ? MailWire.clean(fn, "fromName") : null;
            String helo = config.get("heloName") instanceof String h && h.matches("[A-Za-z0-9.-]{1,253}") ? h : "localhost";

            List<String> to = recipients(inputs.get("to"), "to");
            List<String> cc = recipients(inputs.get("cc"), "cc");
            List<String> bcc = recipients(inputs.get("bcc"), "bcc");
            if (to.isEmpty() && cc.isEmpty() && bcc.isEmpty()) {
                throw new ServiceHandler.ConnectorException("smtp needs at least one recipient in 'to'", false);
            }
            if (to.size() + cc.size() + bcc.size() > MAX_RCPT) {
                throw new ServiceHandler.ConnectorException("More than " + MAX_RCPT + " recipients", false);
            }
            String subject = inputs.get("subject") instanceof String s ? MailWire.clean(s, "subject") : "";
            String text = inputs.get("text") instanceof String t ? t : null;
            String html = inputs.get("html") instanceof String h2 ? h2 : null;
            if ((text == null || text.isEmpty()) && (html == null || html.isEmpty())) {
                throw new ServiceHandler.ConnectorException("smtp needs 'text' and/or 'html'", false);
            }
            if ((text == null ? 0 : text.length()) + (html == null ? 0 : html.length()) > MAX_BODY) {
                throw new ServiceHandler.ConnectorException("Body longer than " + MAX_BODY + " characters", false);
            }
            String replyTo = null;
            if (inputs.get("replyTo") != null) {
                replyTo = Gmail.address(MailWire.clean(String.valueOf(inputs.get("replyTo")), "replyTo"));
                if (replyTo == null) {
                    throw new ServiceHandler.ConnectorException("Invalid replyTo", false);
                }
            }
            String inReplyTo = null;
            if (inputs.get("inReplyTo") instanceof String r && !r.isBlank()) {
                if (!r.matches("<[^<>\\s]{1,200}>")) {
                    throw new ServiceHandler.ConnectorException("inReplyTo must look like <id@host>", false);
                }
                inReplyTo = r;
            }

            String domain = from.substring(from.indexOf('@') + 1);
            String messageId = "<" + UUID.randomUUID() + "@" + domain + ">";
            String message = build(from, fromName, to, cc, subject, text, html, replyTo, inReplyTo, messageId);

            List<String> all = new ArrayList<>(to);
            all.addAll(cc);
            all.addAll(bcc);
            try (MailWire w = MailWire.open(guard, ssl, host, port, sec)) {
                expect(reply(w), 220, "greeting");
                Reply ehlo = command(w, "EHLO " + helo, 250);
                if (sec == MailWire.Security.STARTTLS) {
                    if (!ehlo.has("STARTTLS")) {
                        throw new ServiceHandler.ConnectorException("Server does not offer STARTTLS; refusing to continue in plaintext", false);
                    }
                    command(w, "STARTTLS", 220);
                    w.upgrade();
                    ehlo = command(w, "EHLO " + helo, 250);
                }
                if (user != null) {
                    if (!ehlo.has("AUTH")) {
                        throw new ServiceHandler.ConnectorException("Server does not offer AUTH", false);
                    }
                    String token = Base64.getEncoder().encodeToString(("\0" + user + "\0" + pass).getBytes(StandardCharsets.UTF_8));
                    command(w, "AUTH PLAIN " + token, 235);
                }
                command(w, "MAIL FROM:<" + from + ">", 250);
                for (String r : all) {
                    command(w, "RCPT TO:<" + r + ">", 250, 251);
                }
                command(w, "DATA", 354);
                w.send(dotStuff(message) + "\r\n.\r\n");
                expect(reply(w), 250, "message");
                try {
                    w.send("QUIT\r\n");
                } catch (IOException ignored) {
                    // the message was accepted; a failed goodbye changes nothing
                }
            } catch (IOException e) {
                throw new ServiceHandler.ConnectorException("SMTP I/O error: " + e.getMessage(), true);
            }
            Map<String, Object> out = new LinkedHashMap<>();
            out.put("messageId", messageId);
            out.put("recipients", all.size());
            return out;
        }

        private static List<String> recipients(Object v, String field) throws ServiceHandler.ConnectorException {
            List<String> out = new ArrayList<>();
            if (v == null) {
                return out;
            }
            List<?> items = v instanceof List<?> l ? l : List.of(v);
            for (Object o : items) {
                String a = o instanceof String s ? Gmail.address(MailWire.clean(s, field)) : null;
                if (a == null) {
                    throw new ServiceHandler.ConnectorException("Invalid address in '" + field + "'", false);
                }
                out.add(a);
            }
            return out;
        }
    }

    // ------------------------------------------------------------------ protocol

    record Reply(int code, List<String> lines) {
        boolean has(String keyword) {
            for (String l : lines) {
                String u = l.toUpperCase(Locale.ROOT);
                if (u.equals(keyword) || u.startsWith(keyword + " ") || u.startsWith(keyword + "=")) {
                    return true;
                }
            }
            return false;
        }
    }

    static Reply reply(MailWire w) throws IOException, ServiceHandler.ConnectorException {
        List<String> lines = new ArrayList<>();
        for (int i = 0; i < 200; i++) {
            String l = w.line();
            if (l.length() < 3 || !Character.isDigit(l.charAt(0)) || !Character.isDigit(l.charAt(1)) || !Character.isDigit(l.charAt(2))) {
                throw new IOException("Malformed SMTP reply");
            }
            lines.add(l.length() > 4 ? l.substring(4) : "");
            if (l.length() == 3 || l.charAt(3) == ' ') {
                return new Reply(Integer.parseInt(l.substring(0, 3)), lines);
            }
        }
        throw new IOException("SMTP reply too long");
    }

    private static Reply command(MailWire w, String cmd, int... ok) throws IOException, ServiceHandler.ConnectorException {
        w.send(cmd + "\r\n");
        Reply r = reply(w);
        expect(r, ok, cmd.startsWith("AUTH") ? "AUTH" : cmd.split("[ :]")[0]);
        return r;
    }

    private static void expect(Reply r, int code, String what) throws ServiceHandler.ConnectorException {
        expect(r, new int[] {code}, what);
    }

    private static void expect(Reply r, int[] ok, String what) throws ServiceHandler.ConnectorException {
        for (int c : ok) {
            if (r.code() == c) {
                return;
            }
        }
        // 4xx: try again later; 5xx: the server will not accept this. Never echo the AUTH command, only its outcome.
        String detail = r.lines().isEmpty() ? "" : r.lines().get(0);
        throw new ServiceHandler.ConnectorException("SMTP " + what + " rejected: " + r.code() + " " + detail,
                r.code() >= 400 && r.code() < 500);
    }

    // ------------------------------------------------------------------ message

    static String build(String from, String fromName, List<String> to, List<String> cc, String subject, String text, String html,
            String replyTo, String inReplyTo, String messageId) {
        StringBuilder b = new StringBuilder();
        b.append("From: ").append(fromName == null ? from : displayName(fromName) + " <" + from + ">").append("\r\n");
        if (!to.isEmpty()) {
            b.append("To: ").append(String.join(", ", to)).append("\r\n");
        }
        if (!cc.isEmpty()) {
            b.append("Cc: ").append(String.join(", ", cc)).append("\r\n");
        }
        b.append("Subject: ").append(encodeWords(subject)).append("\r\n");
        b.append("Date: ").append(DateTimeFormatter.RFC_1123_DATE_TIME.format(ZonedDateTime.now(ZoneOffset.UTC))).append("\r\n");
        b.append("Message-ID: ").append(messageId).append("\r\n");
        if (replyTo != null) {
            b.append("Reply-To: ").append(replyTo).append("\r\n");
        }
        if (inReplyTo != null) {
            b.append("In-Reply-To: ").append(inReplyTo).append("\r\n").append("References: ").append(inReplyTo).append("\r\n");
        }
        b.append("MIME-Version: 1.0\r\n");
        boolean hasText = text != null && !text.isEmpty();
        boolean hasHtml = html != null && !html.isEmpty();
        if (hasText && hasHtml) {
            String boundary = "=_wayang_" + UUID.randomUUID().toString().replace("-", "");
            b.append("Content-Type: multipart/alternative; boundary=\"").append(boundary).append("\"\r\n\r\n");
            part(b, boundary, "text/plain", text);
            part(b, boundary, "text/html", html);
            b.append("--").append(boundary).append("--\r\n");
        } else {
            b.append("Content-Type: ").append(hasHtml ? "text/html" : "text/plain").append("; charset=utf-8\r\n")
                    .append("Content-Transfer-Encoding: base64\r\n\r\n").append(b64(hasHtml ? html : text));
        }
        return b.toString();
    }

    private static void part(StringBuilder b, String boundary, String type, String content) {
        b.append("--").append(boundary).append("\r\nContent-Type: ").append(type).append("; charset=utf-8\r\n")
                .append("Content-Transfer-Encoding: base64\r\n\r\n").append(b64(content)).append("\r\n");
    }

    private static String b64(String s) {
        return Base64.getMimeEncoder(76, new byte[] {'\r', '\n'}).encodeToString(s.getBytes(StandardCharsets.UTF_8));
    }

    /** SMTP transparency: a body line that starts with '.' gets a second '.'. Base64 bodies never do, but headers are not trusted either. */
    static String dotStuff(String message) {
        return message.startsWith(".") ? "." + message.replace("\r\n.", "\r\n..") : message.replace("\r\n.", "\r\n..");
    }

    static String displayName(String name) {
        for (int i = 0; i < name.length(); i++) {
            if (name.charAt(i) > 126) {
                return encodeWords(name);
            }
        }
        return "\"" + name.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    /** RFC 2047 encoded-words (B encoding, UTF-8) for anything that is not plain printable ASCII; chunks never split a code point. */
    static String encodeWords(String s) {
        boolean ascii = true;
        for (int i = 0; i < s.length(); i++) {
            if (s.charAt(i) > 126) {
                ascii = false;
                break;
            }
        }
        if (ascii && s.length() <= 900) {
            return s;
        }
        List<String> words = new ArrayList<>();
        StringBuilder cur = new StringBuilder();
        int curBytes = 0;
        for (int i = 0; i < s.length();) {
            int cp = s.codePointAt(i);
            String ch = new String(Character.toChars(cp));
            int n = ch.getBytes(StandardCharsets.UTF_8).length;
            if (curBytes + n > 45) {
                words.add(word(cur.toString()));
                cur.setLength(0);
                curBytes = 0;
            }
            cur.append(ch);
            curBytes += n;
            i += Character.charCount(cp);
        }
        if (cur.length() > 0) {
            words.add(word(cur.toString()));
        }
        return String.join("\r\n ", words);
    }

    private static String word(String s) {
        return "=?UTF-8?B?" + Base64.getEncoder().encodeToString(s.getBytes(StandardCharsets.UTF_8)) + "?=";
    }
}
