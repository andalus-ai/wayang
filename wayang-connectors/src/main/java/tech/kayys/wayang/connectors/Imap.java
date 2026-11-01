package tech.kayys.wayang.connectors;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import javax.net.ssl.SSLSocketFactory;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * Trigger poller {@code imap}: new mail in a mailbox of any IMAP server becomes one event per message.
 *
 * <p>Config: {@code host}, {@code port}, {@code security} (as for smtp; default tls on 993), {@code username}, {@code password},
 * {@code mailbox} (default INBOX), {@code maxResults} (1..50, default 25), {@code includeBody} (default false),
 * <b>{@code allowedSenders} (required unless {@code acceptAnySender: true})</b>, {@code requireAuthenticated} (default true:
 * the receiving server's own {@code Authentication-Results} must show dkim/spf pass) and {@code authservId} (strongly
 * recommended: the server's identifier, so only that server's header is believed).
 *
 * <p>Read-only: the mailbox is opened with EXAMINE and bodies are fetched with BODY.PEEK, so nothing is marked read or
 * deleted. The cursor is {@code uidValidity:lastUid}; the first poll only records the current position (old mail is not
 * replayed), and a changed UIDVALIDITY resets to the current position instead of re-delivering the mailbox.
 *
 * <p>Event: {@code uid, messageId, from, subject, date, mailbox} and {@code body} when asked.
 */
public final class Imap {

    static final int MAX_BODY_BYTES = 65_536;
    static final int MAX_BODY_OUT = 20_000;
    static final int MAX_HEADER_BYTES = 65_536;
    static final long MAX_TOTAL = 8L * 1024 * 1024;

    private static final Pattern LITERAL = Pattern.compile("\\{(\\d+)\\}$");
    private static final Pattern MAILBOX = Pattern.compile("[^\\p{Cntrl}\"\\\\]{1,255}");

    private Imap() {
    }

    public static final class Inbox implements Poller {
        private final UrlGuard guard;
        private final SSLSocketFactory ssl;

        public Inbox(UrlGuard guard) {
            this(guard, (SSLSocketFactory) SSLSocketFactory.getDefault());
        }

        Inbox(UrlGuard guard, SSLSocketFactory ssl) {
            this.guard = guard;
            this.ssl = ssl;
        }

        @Override
        public Result poll(Map<String, Object> config, String cursor) throws Exception {
            boolean any = Boolean.TRUE.equals(config.get("acceptAnySender"));
            Set<String> allowed = new java.util.HashSet<>();
            if (config.get("allowedSenders") instanceof List<?> l) {
                for (Object o : l) {
                    String a = o instanceof String s ? Gmail.address(s) : null;
                    if (a != null) {
                        allowed.add(a);
                    }
                }
            }
            if (allowed.isEmpty() && !any) {
                throw new ServiceHandler.ConnectorException(
                        "imap trigger needs a non-empty 'allowedSenders' (or acceptAnySender: true): anyone can email you", false);
            }
            boolean requireAuth = !Boolean.FALSE.equals(config.get("requireAuthenticated"));
            String authserv = config.get("authservId") instanceof String a && !a.isBlank() ? a.trim().toLowerCase(Locale.ROOT) : null;
            boolean wantBody = Boolean.TRUE.equals(config.get("includeBody"));
            int max = config.get("maxResults") instanceof Number n ? (int) Math.max(1, Math.min(50, n.longValue())) : 25;
            if (!(config.get("host") instanceof String host) || host.isBlank()) {
                throw new ServiceHandler.ConnectorException("imap needs a 'host'", false);
            }
            int port = config.get("port") instanceof Number n ? n.intValue() : 0;
            MailWire.Security sec = MailWire.Security.of(config.get("security"), port == 0 ? 993 : port);
            if (port == 0) {
                port = sec == MailWire.Security.TLS ? 993 : 143;
            }
            if (!(config.get("username") instanceof String user) || user.isEmpty() || !(config.get("password") instanceof String pass)
                    || pass.isEmpty()) {
                throw new ServiceHandler.ConnectorException("imap needs 'username' and 'password' (use ${secret:NAME})", false);
            }
            if (sec == MailWire.Security.NONE) {
                throw new ServiceHandler.ConnectorException("Refusing to send credentials without TLS (security: none)", false);
            }
            MailWire.clean(user, "username");
            MailWire.clean(pass, "password");
            String mailbox = config.get("mailbox") instanceof String m && !m.isBlank() ? m : "INBOX";
            if (!MAILBOX.matcher(mailbox).matches()) {
                throw new ServiceHandler.ConnectorException("Invalid mailbox name", false);
            }
            long wantValidity = -1;
            long last = -1;
            if (cursor != null && !cursor.isBlank()) {
                String[] p = cursor.split(":");
                try {
                    wantValidity = Long.parseLong(p[0]);
                    last = Long.parseLong(p[1]);
                } catch (RuntimeException e) {
                    throw new ServiceHandler.ConnectorException("Corrupt cursor", false);
                }
            }

            try (MailWire w = MailWire.open(guard, ssl, host, port, sec)) {
                Session s = new Session(w);
                String greeting = w.line();
                if (!greeting.startsWith("* OK") && !greeting.startsWith("* PREAUTH")) {
                    throw new ServiceHandler.ConnectorException("Not an IMAP server (greeting: " + clip(greeting, 60) + ")", false);
                }
                if (sec == MailWire.Security.STARTTLS) {
                    s.ok(s.run("STARTTLS"), "STARTTLS");
                    w.upgrade();
                }
                s.ok(s.run("LOGIN " + quote(user) + " " + quote(pass)), "LOGIN");
                Response ex = s.run("EXAMINE " + quote(mailbox));
                s.ok(ex, "EXAMINE");
                long validity = ex.number("UIDVALIDITY");
                long next = ex.number("UIDNEXT");
                if (validity < 0 || next < 1) {
                    throw new ServiceHandler.ConnectorException("Server did not report UIDVALIDITY/UIDNEXT", false);
                }
                if (cursor == null || cursor.isBlank() || validity != wantValidity) {
                    // first poll, or the mailbox was rebuilt: start from now rather than replaying everything
                    return new Result(List.of(), validity + ":" + (next - 1));
                }
                Response found = s.run("UID SEARCH UID " + (last + 1) + ":*");
                s.ok(found, "SEARCH");
                List<Long> uids = new ArrayList<>();
                for (String l : found.untagged) {
                    Matcher m = Pattern.compile("(?i)^\\* SEARCH(.*)$").matcher(l);
                    if (m.matches()) {
                        for (String t : m.group(1).trim().split("\\s+")) {
                            if (t.matches("\\d{1,12}") && Long.parseLong(t) > last) { // "n:*" always includes the newest message
                                uids.add(Long.parseLong(t));
                            }
                        }
                    }
                }
                java.util.Collections.sort(uids);
                if (uids.size() > max) {
                    uids = uids.subList(0, max);
                }
                if (uids.isEmpty()) {
                    return new Result(List.of(), null);
                }
                StringBuilder set = new StringBuilder();
                for (long u : uids) {
                    set.append(set.length() == 0 ? "" : ",").append(u);
                }
                String items = "UID BODY.PEEK[HEADER.FIELDS (FROM SUBJECT DATE MESSAGE-ID AUTHENTICATION-RESULTS CONTENT-TYPE "
                        + "CONTENT-TRANSFER-ENCODING)]" + (wantBody ? " BODY.PEEK[TEXT]<0." + MAX_BODY_BYTES + ">" : "");
                Response fetched = s.run("UID FETCH " + set + " (" + items + ")");
                s.ok(fetched, "FETCH");
                try {
                    w.send(s.nextTag() + " LOGOUT\r\n");
                } catch (IOException ignored) {
                    // done reading
                }

                List<Map<String, Object>> events = new ArrayList<>();
                long newest = last;
                for (Fetch f : fetched.fetches()) {
                    if (f.uid <= last || !uids.contains(f.uid)) {
                        continue;
                    }
                    newest = Math.max(newest, f.uid);
                    Map<String, List<String>> h = MimeText.headers(f.header == null ? "" : new String(f.header, StandardCharsets.UTF_8));
                    String from = Gmail.address(addrPart(MimeText.first(h, "from")));
                    if (from == null || (!any && !allowed.contains(from))) {
                        continue;
                    }
                    if (requireAuth && !authenticated(MimeText.first(h, "authentication-results"), authserv)) {
                        continue;
                    }
                    Map<String, Object> e = new LinkedHashMap<>();
                    e.put("uid", f.uid);
                    e.put("mailbox", mailbox);
                    e.put("messageId", MimeText.first(h, "message-id"));
                    e.put("from", from);
                    e.put("subject", clip(MimeText.decodeWords(MimeText.first(h, "subject") == null ? "" : MimeText.first(h, "subject")), 500));
                    e.put("date", date(MimeText.first(h, "date")));
                    if (wantBody) {
                        String t = f.body == null ? "" : MimeText.text(MimeText.first(h, "content-type"),
                                MimeText.first(h, "content-transfer-encoding"), f.body);
                        e.put("body", clip(t, MAX_BODY_OUT));
                    }
                    events.add(e);
                }
                // every fetched UID counts as seen, matching or not, so filtered mail is not fetched again
                for (long u : uids) {
                    newest = Math.max(newest, u);
                }
                return new Result(events, validity + ":" + newest);
            } catch (IOException e) {
                throw new ServiceHandler.ConnectorException("IMAP I/O error: " + e.getMessage(), true);
            }
        }
    }

    // ------------------------------------------------------------------ protocol

    /** One tagged command's answer: untagged lines (literals spliced out) and fetch records. */
    static final class Response {
        final String status;
        final String text;
        final List<String> untagged = new ArrayList<>();
        final List<List<Literal>> literals = new ArrayList<>();

        Response(String status, String text) {
            this.status = status;
            this.text = text;
        }

        long number(String key) {
            Pattern p = Pattern.compile("(?i)\\[" + key + " (\\d+)\\]");
            for (String l : untagged) {
                Matcher m = p.matcher(l);
                if (m.find()) {
                    return Long.parseLong(m.group(1));
                }
            }
            return -1;
        }

        List<Fetch> fetches() {
            List<Fetch> out = new ArrayList<>();
            Pattern uid = Pattern.compile("(?i)\\bUID (\\d+)");
            for (int i = 0; i < untagged.size(); i++) {
                String l = untagged.get(i);
                if (!l.matches("(?is)^\\* \\d+ FETCH .*")) {
                    continue;
                }
                Matcher m = uid.matcher(l);
                if (!m.find()) {
                    continue;
                }
                Fetch f = new Fetch();
                f.uid = Long.parseLong(m.group(1));
                for (Literal lit : literals.get(i)) {
                    String label = lit.label.toUpperCase(Locale.ROOT);
                    if (label.contains("HEADER")) {
                        f.header = lit.data;
                    } else if (label.contains("TEXT")) {
                        f.body = lit.data;
                    }
                }
                out.add(f);
            }
            return out;
        }
    }

    record Literal(String label, byte[] data) {
    }

    static final class Fetch {
        long uid;
        byte[] header;
        byte[] body;
    }

    static final class Session {
        private final MailWire w;
        private int n;
        private long total;

        Session(MailWire w) {
            this.w = w;
        }

        String nextTag() {
            return "w" + (++n);
        }

        Response run(String command) throws IOException {
            String tag = nextTag();
            w.send(tag + " " + command + "\r\n");
            List<String> untagged = new ArrayList<>();
            List<List<Literal>> lits = new ArrayList<>();
            for (int guard = 0; guard < 20_000; guard++) {
                String line = w.line();
                if (line.startsWith(tag + " ")) {
                    String[] p = line.substring(tag.length() + 1).split(" ", 2);
                    Response r = new Response(p[0].toUpperCase(Locale.ROOT), p.length > 1 ? p[1] : "");
                    r.untagged.addAll(untagged);
                    r.literals.addAll(lits);
                    return r;
                }
                StringBuilder whole = new StringBuilder();
                List<Literal> here = new ArrayList<>();
                String cur = line;
                while (true) {
                    Matcher m = LITERAL.matcher(cur);
                    if (!m.find()) {
                        whole.append(cur);
                        break;
                    }
                    long size = Long.parseLong(m.group(1));
                    String label = cur.substring(0, m.start());
                    whole.append(label);
                    total += size;
                    if (total > MAX_TOTAL) {
                        throw new IOException("Server response too large");
                    }
                    if (size > Math.max(MAX_HEADER_BYTES, MAX_BODY_BYTES) + 1024) {
                        w.skip(size); // never buffer what we will not use
                        here.add(new Literal(label, new byte[0]));
                    } else {
                        here.add(new Literal(label, w.bytes((int) size)));
                    }
                    cur = w.line();
                }
                untagged.add(whole.toString());
                lits.add(here);
            }
            throw new IOException("IMAP response too long");
        }

        void ok(Response r, String what) throws ServiceHandler.ConnectorException {
            if (!"OK".equals(r.status)) {
                // NO/BAD on LOGIN is a credentials problem: retrying will not help
                throw new ServiceHandler.ConnectorException("IMAP " + what + " failed: " + r.status + " " + clip(r.text, 100), false);
            }
        }
    }

    static String quote(String s) {
        return "\"" + s.replace("\\", "\\\\").replace("\"", "\\\"") + "\"";
    }

    static String clip(String s, int n) {
        return s.length() <= n ? s : s.substring(0, n);
    }

    private static String addrPart(String from) {
        return from == null ? null : MimeText.decodeWords(from);
    }

    /** The receiving server's verdict: first Authentication-Results header only (servers prepend theirs; later ones may be forged). */
    static boolean authenticated(String results, String authservId) {
        if (results == null) {
            return false;
        }
        String r = results.trim().toLowerCase(Locale.ROOT);
        if (authservId != null) {
            if (!r.startsWith(authservId) || (r.length() > authservId.length() && ";  \t".indexOf(r.charAt(authservId.length())) < 0)) {
                return false;
            }
        }
        return r.contains("dkim=pass") || r.contains("spf=pass");
    }

    private static long date(String v) {
        if (v == null) {
            return 0;
        }
        try {
            return ZonedDateTime.parse(v.replaceAll("\\s*\\(.*\\)\\s*$", "").trim(), DateTimeFormatter.RFC_1123_DATE_TIME).toInstant().toEpochMilli();
        } catch (RuntimeException e) {
            return 0;
        }
    }
}
