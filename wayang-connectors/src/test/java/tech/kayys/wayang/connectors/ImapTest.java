package tech.kayys.wayang.connectors;

import static org.junit.jupiter.api.Assertions.*;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.Poller;
import tech.kayys.wayang.runtime.ServiceHandler;

class ImapTest {

    /** A scripted IMAP server that serves several connections, one after another. */
    static final class Fake implements AutoCloseable {
        final ServerSocket ss;
        final List<String> commands = new CopyOnWriteArrayList<>();
        final TreeMap<Long, String[]> mail = new TreeMap<>(); // uid -> {header, body}
        volatile long validity = 7;
        volatile boolean loginOk = true;
        volatile SSLContext tls;
        volatile boolean tlsFromStart;
        volatile boolean offerStartTls;
        final Thread thread;

        Fake() throws IOException {
            ss = new ServerSocket(0, 5, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(() -> {
                while (!ss.isClosed()) {
                    try (Socket s = ss.accept()) {
                        serve(s);
                    } catch (Throwable ignored) {
                        // test is over or the client hung up
                    }
                }
            });
            thread.setDaemon(true);
            thread.start();
        }

        int port() {
            return ss.getLocalPort();
        }

        void add(long uid, String from, String subject, String auth, String body) {
            add(uid, from, subject, auth, body, null);
        }

        void add(long uid, String from, String subject, String auth, String body, String contentType) {
            String h = "From: " + from + "\r\nSubject: " + subject + "\r\nDate: Sat, 24 Oct 2026 10:00:00 +0000\r\nMessage-ID: <m" + uid
                    + "@x.example>\r\n" + (auth == null ? "" : "Authentication-Results: " + auth + "\r\n")
                    + (contentType == null ? "" : "Content-Type: " + contentType + "\r\n") + "\r\n";
            mail.put(uid, new String[] {h, body});
        }

        private static String line(InputStream in) throws IOException {
            ByteArrayOutputStream b = new ByteArrayOutputStream();
            int c;
            while ((c = in.read()) != -1) {
                if (c == '\n') {
                    String s = b.toString(StandardCharsets.UTF_8);
                    return s.endsWith("\r") ? s.substring(0, s.length() - 1) : s;
                }
                b.write(c);
            }
            return null;
        }

        private void serve(Socket raw) throws Exception {
            Socket s = raw;
            if (tlsFromStart) {
                s = upgrade(raw);
            }
            InputStream in = new BufferedInputStream(s.getInputStream());
            OutputStream out = s.getOutputStream();
            out.write("* OK fake IMAP ready\r\n".getBytes(StandardCharsets.UTF_8));
            String l;
            while ((l = line(in)) != null) {
                String tag = l.substring(0, l.indexOf(' '));
                String cmd = l.substring(tag.length() + 1);
                commands.add(cmd.startsWith("LOGIN") ? "LOGIN <redacted>" : cmd);
                if (cmd.equals("STARTTLS")) {
                    reply(out, tag + " OK go ahead");
                    s = upgrade(s);
                    in = new BufferedInputStream(s.getInputStream());
                    out = s.getOutputStream();
                } else if (cmd.startsWith("LOGIN")) {
                    reply(out, loginOk && cmd.equals("LOGIN \"bob\" \"p\\\"w\"") ? tag + " OK logged in" : tag + " NO [AUTHENTICATIONFAILED] bad");
                } else if (cmd.startsWith("EXAMINE")) {
                    long next = mail.isEmpty() ? 1 : mail.lastKey() + 1;
                    reply(out, "* " + mail.size() + " EXISTS\r\n* OK [UIDVALIDITY " + validity + "] ok\r\n* OK [UIDNEXT " + next + "] ok\r\n"
                            + tag + " OK [READ-ONLY] done");
                } else if (cmd.startsWith("UID SEARCH UID ")) {
                    long from = Long.parseLong(cmd.substring(15, cmd.indexOf(':')));
                    StringBuilder r = new StringBuilder("* SEARCH");
                    boolean any = false;
                    for (long u : mail.keySet()) {
                        if (u >= from) {
                            r.append(' ').append(u);
                            any = true;
                        }
                    }
                    if (!any && !mail.isEmpty()) {
                        r.append(' ').append(mail.lastKey()); // "n:*" always includes the newest message
                    }
                    reply(out, r + "\r\n" + tag + " OK done");
                } else if (cmd.startsWith("UID FETCH ")) {
                    String set = cmd.substring(10, cmd.indexOf(' ', 10));
                    boolean body = cmd.contains("BODY.PEEK[TEXT]");
                    int seq = 0;
                    for (String u : set.split(",")) {
                        String[] m = mail.get(Long.parseLong(u));
                        seq++;
                        if (m == null) {
                            continue;
                        }
                        byte[] h = m[0].getBytes(StandardCharsets.UTF_8);
                        out.write(("* " + seq + " FETCH (UID " + u + " BODY[HEADER.FIELDS (FROM SUBJECT)] {" + h.length + "}\r\n")
                                .getBytes(StandardCharsets.UTF_8));
                        out.write(h);
                        if (body) {
                            byte[] b = m[1].getBytes(StandardCharsets.UTF_8);
                            out.write((" BODY[TEXT]<0> {" + b.length + "}\r\n").getBytes(StandardCharsets.UTF_8));
                            out.write(b);
                        }
                        out.write(")\r\n".getBytes(StandardCharsets.UTF_8));
                    }
                    reply(out, tag + " OK done");
                } else if (cmd.equals("LOGOUT")) {
                    reply(out, "* BYE\r\n" + tag + " OK bye");
                    return;
                } else {
                    reply(out, tag + " BAD unknown");
                }
            }
        }

        private static void reply(OutputStream o, String s) throws IOException {
            o.write((s + "\r\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
        }

        private Socket upgrade(Socket s) throws IOException {
            SSLSocket t = (SSLSocket) tls.getSocketFactory().createSocket(s, null, s.getPort(), true);
            t.setUseClientMode(false);
            t.startHandshake();
            return t;
        }

        @Override
        public void close() throws IOException {
            ss.close();
        }
    }

    private final UrlGuard local = new UrlGuard(true, Set.of());
    private Fake imap;
    private TestTls t;

    @AfterEach
    void down() throws Exception {
        if (imap != null) {
            imap.close();
        }
    }

    private Poller poller() throws Exception {
        imap = new Fake();
        t = new TestTls();
        imap.tls = t.server;
        imap.tlsFromStart = true;
        return new Imap.Inbox(local, t.client.getSocketFactory());
    }

    private Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("host", "127.0.0.1");
        m.put("port", imap.port());
        m.put("security", "tls");
        m.put("username", "bob");
        m.put("password", "p\"w");
        m.put("allowedSenders", List.of("alice@example.org"));
        m.put("authservId", "mx.example.com");
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static final String PASS = "mx.example.com; dkim=pass header.d=example.org";

    @Test
    void firstPollOnlyRecordsPositionThenDeliversNewMail() throws Exception {
        Poller p = poller();
        imap.add(1, "Alice <alice@example.org>", "old", PASS, "old mail");
        Poller.Result first = p.poll(cfg(), null);
        assertEquals(0, first.events().size());
        assertEquals("7:1", first.nextCursor());

        imap.add(2, "Alice <alice@example.org>", "=?UTF-8?B?SGFsbyDimJU=?=", PASS, "hello");
        Poller.Result second = p.poll(cfg(), first.nextCursor());
        assertEquals(1, second.events().size());
        Map<String, Object> e = second.events().get(0);
        assertEquals(2L, e.get("uid"));
        assertEquals("alice@example.org", e.get("from"));
        assertEquals("Halo ☕", e.get("subject"));
        assertEquals("<m2@x.example>", e.get("messageId"));
        assertEquals(1792836000000L, e.get("date"));
        assertNull(e.get("body"));
        assertEquals("7:2", second.nextCursor());

        Poller.Result third = p.poll(cfg(), second.nextCursor());
        assertEquals(0, third.events().size(), "the quirk 'n:* returns the newest' must not re-deliver");
        assertTrue(imap.commands.stream().anyMatch(c -> c.startsWith("EXAMINE")), "read-only open");
        assertTrue(imap.commands.stream().noneMatch(c -> c.startsWith("SELECT") || c.contains("STORE") || c.contains("EXPUNGE")));
    }

    @Test
    void filtersBySenderAndByTheReceivingServersVerdict() throws Exception {
        Poller p = poller();
        String cur = p.poll(cfg(), null).nextCursor();
        imap.add(1, "Mallory <mallory@example.net>", "not allowed", PASS, "x");
        imap.add(2, "alice@example.org", "no auth header", null, "x");
        imap.add(3, "alice@example.org", "forged by a relay", "evil.example; dkim=pass", "x");
        imap.add(4, "alice@example.org", "failed", "mx.example.com; dkim=fail; spf=fail", "x");
        imap.add(5, "alice@example.org", "genuine", PASS, "x");
        imap.add(6, "\"alice@example.org\" <mallory@example.net>", "display-name trick", PASS, "x");
        Poller.Result r = p.poll(cfg(), cur);
        assertEquals(1, r.events().size());
        assertEquals("genuine", r.events().get(0).get("subject"));
        assertEquals("7:6", r.nextCursor(), "filtered mail still counts as seen");
    }

    @Test
    void authservIdOptionalButFirstHeaderIsWhatCounts() throws Exception {
        Poller p = poller();
        String cur = p.poll(cfg("authservId", ""), null).nextCursor();
        imap.add(1, "alice@example.org", "any server", "whoever.example; spf=pass", "x");
        assertEquals(1, p.poll(cfg("authservId", ""), cur).events().size());
        assertEquals(0, p.poll(cfg("authservId", "mx.example.com"), cur).events().size());
    }

    @Test
    void requireAuthenticatedCanBeSwitchedOff() throws Exception {
        Poller p = poller();
        String cur = p.poll(cfg(), null).nextCursor();
        imap.add(1, "alice@example.org", "plain", null, "x");
        assertEquals(1, p.poll(cfg("requireAuthenticated", false), cur).events().size());
    }

    @Test
    void refusesToRunOpenToTheWorld() throws Exception {
        Poller p = poller();
        assertThrows(ServiceHandler.ConnectorException.class, () -> p.poll(cfg("allowedSenders", List.of()), null));
        assertEquals(0, imap.commands.size());
        // opting in explicitly works
        assertNotNull(p.poll(cfg("allowedSenders", List.of(), "acceptAnySender", true), null).nextCursor());
    }

    @Test
    void bodiesAreOptInAndDecoded() throws Exception {
        Poller p = poller();
        String cur = p.poll(cfg(), null).nextCursor();
        String mp = "--b1\r\nContent-Type: text/html; charset=utf-8\r\n\r\n<p>html version</p>\r\n--b1\r\n"
                + "Content-Type: text/plain; charset=utf-8\r\nContent-Transfer-Encoding: quoted-printable\r\n\r\n"
                + "Caf=C3=A9 au lait=\r\n and more\r\n--b1--\r\n";
        imap.add(1, "alice@example.org", "mime", PASS, mp, "multipart/alternative; boundary=\"b1\"");
        Poller.Result r = p.poll(cfg("includeBody", true), cur);
        assertEquals("Café au lait and more", r.events().get(0).get("body"));
    }

    @Test
    void wrongCredentialsArePermanentAndNotLeaked() throws Exception {
        Poller p = poller();
        imap.loginOk = false;
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> p.poll(cfg(), null));
        assertFalse(e.retryable());
        assertFalse(e.getMessage().contains("p\"w"));
    }

    @Test
    void uidValidityChangeResetsInsteadOfReplaying() throws Exception {
        Poller p = poller();
        imap.add(1, "alice@example.org", "a", PASS, "x");
        imap.add(2, "alice@example.org", "b", PASS, "x");
        imap.validity = 99;
        Poller.Result r = p.poll(cfg(), "7:0");
        assertEquals(0, r.events().size());
        assertEquals("99:2", r.nextCursor());
    }

    @Test
    void maxResultsBatchesAcrossPolls() throws Exception {
        Poller p = poller();
        String cur = p.poll(cfg(), null).nextCursor();
        for (int i = 1; i <= 5; i++) {
            imap.add(i, "alice@example.org", "m" + i, PASS, "x");
        }
        Poller.Result a = p.poll(cfg("maxResults", 2), cur);
        assertEquals(List.of(1L, 2L), a.events().stream().map(e -> e.get("uid")).toList());
        Poller.Result b = p.poll(cfg("maxResults", 2), a.nextCursor());
        assertEquals(List.of(3L, 4L), b.events().stream().map(e -> e.get("uid")).toList());
        Poller.Result c = p.poll(cfg("maxResults", 2), b.nextCursor());
        assertEquals(List.of(5L), c.events().stream().map(e -> e.get("uid")).toList());
    }

    @Test
    void corruptCursorAndBadMailboxAreRejected() throws Exception {
        Poller p = poller();
        assertThrows(ServiceHandler.ConnectorException.class, () -> p.poll(cfg(), "garbage"));
        assertThrows(ServiceHandler.ConnectorException.class, () -> p.poll(cfg("mailbox", "INBOX\r\nA2 DELETE x"), null));
        assertThrows(ServiceHandler.ConnectorException.class, () -> p.poll(cfg("security", "none"), null));
    }

    @Test
    void starttlsPathAndPlaintextRefusal() throws Exception {
        imap = new Fake();
        t = new TestTls();
        imap.tls = t.server;
        // STARTTLS: server upgrades after the command
        Poller p = new Imap.Inbox(local, t.client.getSocketFactory());
        assertEquals("7:0", p.poll(cfg("security", "starttls"), null).nextCursor());
        int st = imap.commands.indexOf("STARTTLS");
        int login = imap.commands.indexOf("LOGIN <redacted>");
        assertTrue(st >= 0 && login > st);
    }

    @Test
    void strictGuardRefusesLoopback() throws Exception {
        imap = new Fake();
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new Imap.Inbox(UrlGuard.strict()).poll(cfg(), null));
        assertTrue(e.getMessage().contains("internal"));
        assertEquals(0, imap.commands.size());
    }
}
