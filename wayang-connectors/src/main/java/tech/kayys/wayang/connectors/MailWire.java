package tech.kayys.wayang.connectors;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.Closeable;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Socket;
import java.nio.charset.StandardCharsets;
import java.util.Locale;

import javax.net.ssl.SSLParameters;
import javax.net.ssl.SSLSocket;
import javax.net.ssl.SSLSocketFactory;

import tech.kayys.wayang.runtime.ServiceHandler;

/**
 * A CRLF line-oriented socket shared by the SMTP and IMAP clients: SSRF-guarded connect (resolve once, connect to that
 * address), TLS with hostname verification, bounded line and literal sizes, timeouts everywhere.
 */
final class MailWire implements Closeable {

    static final int CONNECT_MS = 10_000;
    static final int READ_MS = 30_000;
    static final int MAX_LINE = 8192;

    /** How the channel is protected. {@code none} is refused whenever credentials would cross it. */
    enum Security {
        TLS, STARTTLS, NONE;

        static Security of(Object v, int port) throws ServiceHandler.ConnectorException {
            if (v == null) {
                return port == 465 || port == 993 ? TLS : STARTTLS;
            }
            try {
                return valueOf(String.valueOf(v).toUpperCase(Locale.ROOT));
            } catch (IllegalArgumentException e) {
                throw new ServiceHandler.ConnectorException("security must be tls, starttls or none", false);
            }
        }
    }

    private final String host;
    private final SSLSocketFactory ssl;
    private Socket socket;
    private InputStream in;
    private OutputStream out;

    private MailWire(String host, SSLSocketFactory ssl) {
        this.host = host;
        this.ssl = ssl;
    }

    static MailWire open(UrlGuard guard, SSLSocketFactory ssl, String host, int port, Security security)
            throws IOException, ServiceHandler.ConnectorException {
        if (port < 1 || port > 65535) {
            throw new ServiceHandler.ConnectorException("Invalid port", false);
        }
        InetAddress addr;
        try {
            addr = guard.checkHost(host);
        } catch (IllegalArgumentException e) {
            throw new ServiceHandler.ConnectorException(e.getMessage(), false);
        }
        MailWire w = new MailWire(host, ssl);
        Socket s = new Socket();
        try {
            s.connect(new InetSocketAddress(addr, port), CONNECT_MS);
            s.setSoTimeout(READ_MS);
            w.socket = s;
            if (security == Security.TLS) {
                w.upgrade();
            } else {
                w.bind(s);
            }
        } catch (IOException | RuntimeException e) {
            s.close();
            throw e;
        }
        return w;
    }

    private void bind(Socket s) throws IOException {
        this.in = new BufferedInputStream(s.getInputStream(), 8192);
        this.out = s.getOutputStream();
    }

    /** Wraps the current socket in TLS (implicit TLS, or after STARTTLS / STARTTLS-equivalent). Verifies the host name. */
    void upgrade() throws IOException {
        SSLSocket t = (SSLSocket) ssl.createSocket(socket, host, socket.getPort(), true);
        SSLParameters p = t.getSSLParameters();
        p.setEndpointIdentificationAlgorithm("HTTPS");
        t.setSSLParameters(p);
        t.setSoTimeout(READ_MS);
        t.startHandshake();
        this.socket = t;
        bind(t);
    }

    /** One line without its CRLF (a bare LF is tolerated). Over-long lines are an error, not truncated. */
    String line() throws IOException {
        ByteArrayOutputStream b = new ByteArrayOutputStream(80);
        int c;
        while ((c = in.read()) != -1) {
            if (c == '\n') {
                byte[] a = b.toByteArray();
                int n = a.length > 0 && a[a.length - 1] == '\r' ? a.length - 1 : a.length;
                return new String(a, 0, n, StandardCharsets.UTF_8);
            }
            if (b.size() >= MAX_LINE) {
                throw new IOException("Server line too long");
            }
            b.write(c);
        }
        throw new IOException("Connection closed by server");
    }

    byte[] bytes(int n) throws IOException {
        byte[] a = new byte[n];
        int off = 0;
        while (off < n) {
            int r = in.read(a, off, n - off);
            if (r < 0) {
                throw new IOException("Connection closed by server");
            }
            off += r;
        }
        return a;
    }

    void skip(long n) throws IOException {
        long left = n;
        byte[] buf = new byte[4096];
        while (left > 0) {
            int r = in.read(buf, 0, (int) Math.min(buf.length, left));
            if (r < 0) {
                throw new IOException("Connection closed by server");
            }
            left -= r;
        }
    }

    void send(String s) throws IOException {
        out.write(s.getBytes(StandardCharsets.UTF_8));
        out.flush();
    }

    @Override
    public void close() {
        try {
            if (socket != null) {
                socket.close();
            }
        } catch (IOException ignored) {
            // closing
        }
    }

    /** Control characters (and anything that could break a line-based protocol) are never allowed in a value we send. */
    static String clean(String v, String what) throws ServiceHandler.ConnectorException {
        for (int i = 0; i < v.length(); i++) {
            char c = v.charAt(i);
            if (c < 0x20 || c == 0x7f) {
                throw new ServiceHandler.ConnectorException(what + " contains control characters", false);
            }
        }
        return v;
    }
}
