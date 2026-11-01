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
import java.util.Base64;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.CopyOnWriteArrayList;

import javax.net.ssl.SSLContext;
import javax.net.ssl.SSLSocket;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import tech.kayys.wayang.runtime.ServiceHandler;

class SmtpTest {

    /** One-connection scripted SMTP server. */
    static final class Fake implements AutoCloseable {
        final ServerSocket ss;
        final List<String> commands = new CopyOnWriteArrayList<>();
        volatile String data;
        volatile boolean offerStartTls;
        volatile boolean offerAuth = true;
        volatile int rcptCode = 250;
        volatile int dataCode = 250;
        volatile boolean tlsFromStart;
        volatile SSLContext tls;
        volatile Throwable failure;
        final Thread thread;

        Fake() throws IOException {
            ss = new ServerSocket(0, 1, InetAddress.getByName("127.0.0.1"));
            thread = new Thread(this::serve);
            thread.setDaemon(true);
        }

        int port() {
            return ss.getLocalPort();
        }

        Fake start() {
            thread.start();
            return this;
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

        private static void say(OutputStream o, String s) throws IOException {
            o.write((s + "\r\n").getBytes(StandardCharsets.UTF_8));
            o.flush();
        }

        private void serve() {
            try (Socket raw = ss.accept()) {
                Socket s = raw;
                if (tlsFromStart) {
                    s = upgrade(raw);
                }
                InputStream in = new BufferedInputStream(s.getInputStream());
                OutputStream out = s.getOutputStream();
                say(out, "220 fake ESMTP");
                String l;
                while ((l = line(in)) != null) {
                    commands.add(l.startsWith("AUTH PLAIN") ? "AUTH PLAIN <redacted>" : l);
                    if (l.startsWith("AUTH PLAIN ")) {
                        String cred = new String(Base64.getDecoder().decode(l.substring(11)), StandardCharsets.UTF_8);
                        commands.add("CRED " + cred.replace('\0', '|'));
                        say(out, cred.equals("\0bob\0secret") ? "235 ok" : "535 bad credentials");
                    } else if (l.startsWith("EHLO")) {
                        out.write(("250-fake\r\n" + (offerStartTls && !(s instanceof SSLSocket) ? "250-STARTTLS\r\n" : "")
                                + (offerAuth ? "250-AUTH PLAIN\r\n" : "") + "250 8BITMIME\r\n").getBytes(StandardCharsets.UTF_8));
                        out.flush();
                    } else if (l.equals("STARTTLS")) {
                        say(out, "220 go");
                        s = upgrade(s);
                        in = new BufferedInputStream(s.getInputStream());
                        out = s.getOutputStream();
                    } else if (l.startsWith("MAIL FROM")) {
                        say(out, "250 ok");
                    } else if (l.startsWith("RCPT TO")) {
                        say(out, rcptCode + " rcpt");
                    } else if (l.equals("DATA")) {
                        say(out, "354 go");
                        StringBuilder d = new StringBuilder();
                        while (!".".equals(l = line(in))) {
                            d.append(l).append("\r\n");
                        }
                        data = d.toString();
                        say(out, dataCode + " queued");
                    } else if (l.equals("QUIT")) {
                        say(out, "221 bye");
                        return;
                    } else {
                        say(out, "500 what");
                    }
                }
            } catch (Throwable t) {
                failure = t;
            }
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
    private Fake smtp;

    @BeforeEach
    void up() throws Exception {
        smtp = new Fake();
    }

    @AfterEach
    void down() throws Exception {
        smtp.close();
    }

    private Map<String, Object> cfg(Object... kv) {
        Map<String, Object> m = new java.util.LinkedHashMap<>();
        m.put("host", "127.0.0.1");
        m.put("port", smtp.port());
        m.put("from", "wayang@example.com");
        for (int i = 0; i < kv.length; i += 2) {
            m.put((String) kv[i], kv[i + 1]);
        }
        return m;
    }

    private static String body(String data) {
        String b64 = data.substring(data.indexOf("\r\n\r\n") + 4).replace("\r\n", "");
        return new String(Base64.getDecoder().decode(b64), StandardCharsets.UTF_8);
    }

    @Test
    void sendsMessageWithBccOnlyInEnvelope() throws Exception {
        smtp.start();
        Map<String, Object> out = new Smtp.Sender(local).call(cfg("security", "none"),
                Map.of("to", List.of("a@example.org", "Bob <b@example.org>"), "bcc", "secret@example.net", "subject", "Hello", "text", "Hi\r\n.\r\nthere"));
        assertEquals(3, out.get("recipients"));
        assertTrue(((String) out.get("messageId")).endsWith("@example.com>"));
        assertTrue(smtp.commands.contains("RCPT TO:<a@example.org>"));
        assertTrue(smtp.commands.contains("RCPT TO:<b@example.org>"));
        assertTrue(smtp.commands.contains("RCPT TO:<secret@example.net>"));
        assertTrue(smtp.data.contains("To: a@example.org, b@example.org"));
        assertFalse(smtp.data.contains("secret@example.net"), "Bcc must not appear in headers");
        assertTrue(smtp.data.contains("Subject: Hello"));
        assertEquals("Hi\r\n.\r\nthere", body(smtp.data));
        for (int i = 0; i < 100 && !smtp.commands.contains("QUIT"); i++) {
            Thread.sleep(20); // the server thread records QUIT just after the client has finished
        }
        assertTrue(smtp.commands.contains("QUIT"));
    }

    @Test
    void nonAsciiSubjectAndMultipart() throws Exception {
        smtp.start();
        new Smtp.Sender(local).call(cfg("security", "none", "fromName", "Wayang \"Bot\""),
                Map.of("to", "a@example.org", "subject", "Selamat pagi, dunia ☕", "text", "plain", "html", "<b>rich</b>"));
        assertTrue(smtp.data.contains("Subject: =?UTF-8?B?"));
        assertFalse(smtp.data.contains("☕"));
        assertTrue(smtp.data.contains("multipart/alternative"));
        assertTrue(smtp.data.contains("From: \"Wayang \\\"Bot\\\"\" <wayang@example.com>"));
    }

    @Test
    void headerInjectionIsRejectedBeforeConnecting() throws Exception {
        // server is never started: any connection attempt would hang the test, so these must fail first
        var s = new Smtp.Sender(local);
        List<Map<String, Object>> bad = List.of(
                Map.of("to", "a@example.org", "subject", "x\r\nBcc: evil@example.net", "text", "t"),
                Map.of("to", "a@example.org\r\nRCPT TO:<evil@example.net>", "text", "t"),
                Map.of("to", "a@example.org", "replyTo", "x@example.org\nBcc: y@example.net", "text", "t"),
                Map.of("to", "a@example.org", "text", "t", "inReplyTo", "<a>\r\nBcc: x"),
                Map.of("text", "t"),
                Map.of("to", "a@example.org"));
        for (Map<String, Object> in : bad) {
            var e = assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(cfg("security", "none"), in), String.valueOf(in));
            assertFalse(e.retryable(), "a validation error, not a connection timeout: " + e.getMessage());
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(cfg("security", "none", "fromName", "x\ny"),
                Map.of("to", "a@example.org", "text", "t")));
        assertEquals(0, smtp.commands.size());
    }

    @Test
    void credentialsNeverCrossPlaintext() throws Exception {
        var s = new Smtp.Sender(local);
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> s.call(
                cfg("security", "none", "username", "bob", "password", "secret"), Map.of("to", "a@example.org", "text", "t")));
        assertTrue(e.getMessage().contains("without TLS"));
        assertEquals(0, smtp.commands.size());
    }

    @Test
    void starttlsIsRequiredNotOptional() throws Exception {
        smtp.offerStartTls = false; // a downgrade attempt: server strips STARTTLS from EHLO
        smtp.start();
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new Smtp.Sender(local).call(
                cfg("security", "starttls", "username", "bob", "password", "secret"), Map.of("to", "a@example.org", "text", "t")));
        assertTrue(e.getMessage().contains("STARTTLS"));
        assertFalse(e.retryable());
        assertTrue(smtp.commands.stream().noneMatch(c -> c.startsWith("AUTH") || c.startsWith("CRED")), "no credentials after a downgrade");
    }

    @Test
    void starttlsThenAuthWithVerifiedCertificate() throws Exception {
        TestTls t = new TestTls();
        smtp.tls = t.server;
        smtp.offerStartTls = true;
        smtp.start();
        Map<String, Object> out = new Smtp.Sender(local, t.client.getSocketFactory()).call(
                cfg("security", "starttls", "username", "bob", "password", "secret"), Map.of("to", "a@example.org", "text", "secure hello"));
        assertEquals(1, out.get("recipients"));
        assertEquals("secure hello", body(smtp.data));
        int starttls = smtp.commands.indexOf("STARTTLS");
        int auth = smtp.commands.indexOf("AUTH PLAIN <redacted>");
        assertTrue(starttls >= 0 && auth > starttls, "AUTH only after STARTTLS: " + smtp.commands);
        assertTrue(smtp.commands.contains("CRED |bob|secret"));
        assertNull(smtp.failure);
    }

    @Test
    void implicitTlsRejectsUntrustedCertificate() throws Exception {
        TestTls t = new TestTls();
        smtp.tls = t.server;
        smtp.tlsFromStart = true;
        smtp.start();
        // default trust store does not know the throw-away certificate
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new Smtp.Sender(local).call(
                cfg("security", "tls", "username", "bob", "password", "secret"), Map.of("to", "a@example.org", "text", "t")));
        assertTrue(e.retryable());
        assertTrue(smtp.commands.stream().noneMatch(c -> c.startsWith("AUTH")));
    }

    @Test
    void certificateForAnotherNameIsRejectedEvenIfTrusted() throws Exception {
        TestTls t = new TestTls(); // trusted, but issued for the IP 127.0.0.1 only
        smtp.tls = t.server;
        smtp.tlsFromStart = true;
        smtp.start();
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new Smtp.Sender(local, t.client.getSocketFactory()).call(
                cfg("host", "localhost", "security", "tls", "username", "bob", "password", "secret"), Map.of("to", "a@example.org", "text", "t")));
        assertTrue(e.getMessage().startsWith("SMTP I/O error"), e.getMessage());
        assertTrue(smtp.commands.stream().noneMatch(c -> c.startsWith("AUTH")));
    }

    @Test
    void implicitTlsWorksWithTrustedCertificate() throws Exception {
        TestTls t = new TestTls();
        smtp.tls = t.server;
        smtp.tlsFromStart = true;
        smtp.start();
        new Smtp.Sender(local, t.client.getSocketFactory()).call(cfg("security", "tls", "username", "bob", "password", "secret"),
                Map.of("to", "a@example.org", "text", "hi"));
        assertEquals("hi", body(smtp.data));
    }

    @Test
    void wrongPasswordIsPermanentAndDoesNotEchoCredentials() throws Exception {
        TestTls t = new TestTls();
        smtp.tls = t.server;
        smtp.offerStartTls = true;
        smtp.start();
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new Smtp.Sender(local, t.client.getSocketFactory()).call(
                cfg("security", "starttls", "username", "bob", "password", "wrong"), Map.of("to", "a@example.org", "text", "t")));
        assertFalse(e.retryable());
        assertTrue(e.getMessage().contains("535"));
        assertFalse(e.getMessage().contains("wrong"));
    }

    @Test
    void temporaryAndPermanentRejections() throws Exception {
        smtp.rcptCode = 451;
        smtp.start();
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new Smtp.Sender(local).call(cfg("security", "none"),
                Map.of("to", "a@example.org", "text", "t")));
        assertTrue(e.retryable());
    }

    @Test
    void permanentRecipientRefusalIsNotRetried() throws Exception {
        smtp.rcptCode = 550;
        smtp.start();
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new Smtp.Sender(local).call(cfg("security", "none"),
                Map.of("to", "a@example.org", "text", "t")));
        assertFalse(e.retryable());
    }

    @Test
    void privateHostsAreRefusedByTheStrictGuard() {
        var e = assertThrows(ServiceHandler.ConnectorException.class, () -> new Smtp.Sender(UrlGuard.strict()).call(
                Map.of("host", "127.0.0.1", "port", 25, "security", "none", "from", "a@example.com"), Map.of("to", "a@example.org", "text", "t")));
        assertTrue(e.getMessage().contains("internal"));
    }

    @Test
    void tooManyRecipients() {
        List<String> many = new java.util.ArrayList<>();
        for (int i = 0; i < 51; i++) {
            many.add("u" + i + "@example.org");
        }
        assertThrows(ServiceHandler.ConnectorException.class, () -> new Smtp.Sender(local).call(cfg("security", "none"),
                Map.of("to", many, "text", "t")));
    }
}
