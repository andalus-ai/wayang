package tech.kayys.wayang.endpoints.ftp;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.apache.commons.net.ftp.FTP;
import org.apache.commons.net.ftp.FTPClient;
import org.apache.commons.net.ftp.FTPFile;
import org.apache.commons.net.ftp.FTPReply;
import org.apache.commons.net.ftp.FTPSClient;

/** The production {@link Ftp.Opener}: commons-net, passive mode, explicit FTPS with a verified certificate and host name unless the node opted out. */
final class CommonsNetOpener implements Ftp.Opener {

    @Override
    public Ftp.Session open(Ftp.Target t) throws Exception {
        FTPClient c;
        if (t.tls()) {
            FTPSClient s = new FTPSClient("TLS", false);
            s.setHostnameVerifier(new javax.net.ssl.HostnameVerifier() {
                @Override
                public boolean verify(String host, javax.net.ssl.SSLSession session) {
                    return javax.net.ssl.HttpsURLConnection.getDefaultHostnameVerifier().verify(host, session);
                }
            });
            c = s;
        } else {
            c = new FTPClient();
        }
        c.setConnectTimeout(10_000);
        c.setDefaultTimeout(30_000);
        c.setDataTimeout(java.time.Duration.ofSeconds(30));
        c.setControlKeepAliveTimeout(java.time.Duration.ofSeconds(10));
        try {
            c.connect(t.host(), t.port());
            if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
                throw new IOException("ftp server refused the connection");
            }
            if (c instanceof FTPSClient s) {
                s.execPBSZ(0);
                s.execPROT("P"); // protect the data channel too
            }
            if (!c.login(t.user(), t.password())) {
                throw new SecurityException("ftp login refused");
            }
            c.setFileType(FTP.BINARY_FILE_TYPE);
            c.enterLocalPassiveMode();
            return new Session(c);
        } catch (Exception e) {
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // the original failure is the one that matters
            }
            throw e;
        }
    }

    private static final class Session implements Ftp.Session {
        private final FTPClient c;

        Session(FTPClient c) {
            this.c = c;
        }

        private void ok(String what) throws IOException {
            if (!FTPReply.isPositiveCompletion(c.getReplyCode())) {
                throw new IOException(what + " refused (" + c.getReplyCode() + ")");
            }
        }

        @Override
        public List<Ftp.Entry> list(String dir) throws Exception {
            List<Ftp.Entry> out = new ArrayList<>();
            for (FTPFile f : c.mlistDir(dir.equals(".") ? null : dir)) {
                if (out.size() >= Ftp.MAX_LIST) {
                    break;
                }
                if (f == null || f.getName() == null || f.getName().contains("/")) {
                    continue;
                }
                long m = f.getTimestampInstant() == null ? 0 : f.getTimestampInstant().getEpochSecond();
                out.add(new Ftp.Entry(f.getName(), f.isFile(), Math.max(0, f.getSize()), m));
            }
            ok("listing");
            return out;
        }

        @Override
        public byte[] read(String path, int max) throws Exception {
            try (InputStream in = c.retrieveFileStream(path)) {
                if (in == null) {
                    throw new IOException("read refused (" + c.getReplyCode() + ")");
                }
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > max) {
                        c.abort();
                        return null;
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            } finally {
                try {
                    c.completePendingCommand();
                } catch (Exception ignored) {
                    // an aborted transfer reports a failure here
                }
            }
        }

        @Override
        public void write(String path, byte[] data, boolean overwrite) throws Exception {
            if (!overwrite && c.mlistFile(path) != null) {
                throw new java.nio.file.FileAlreadyExistsException("exists");
            }
            String tmp = path + ".wayang-" + UUID.randomUUID().toString().substring(0, 8) + ".tmp";
            if (!c.storeFile(tmp, new ByteArrayInputStream(data))) {
                throw new IOException("write refused (" + c.getReplyCode() + ")");
            }
            if (overwrite && c.mlistFile(path) != null) {
                c.deleteFile(path);
            }
            if (!c.rename(tmp, path)) {
                try {
                    c.deleteFile(tmp);
                } catch (Exception ignored) {
                    // best effort: do not leave a half-published temp file when the rename failed
                }
                throw new IOException("rename refused (" + c.getReplyCode() + ")");
            }
        }

        @Override
        public void delete(String path) throws Exception {
            if (!c.deleteFile(path)) {
                throw new IOException("delete refused (" + c.getReplyCode() + ")");
            }
        }

        @Override
        public void close() {
            try {
                c.logout();
            } catch (Exception ignored) {
                // closing
            }
            try {
                c.disconnect();
            } catch (Exception ignored) {
                // closing
            }
        }
    }
}
