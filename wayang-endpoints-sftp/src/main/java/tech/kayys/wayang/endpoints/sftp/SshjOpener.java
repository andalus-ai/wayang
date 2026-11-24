package tech.kayys.wayang.endpoints.sftp;

import java.io.ByteArrayOutputStream;
import java.io.InputStream;
import java.io.OutputStream;
import java.util.ArrayList;
import java.util.EnumSet;
import java.util.List;
import java.util.UUID;

import net.schmizz.sshj.SSHClient;
import net.schmizz.sshj.sftp.OpenMode;
import net.schmizz.sshj.sftp.RemoteFile;
import net.schmizz.sshj.sftp.RemoteResourceInfo;
import net.schmizz.sshj.sftp.SFTPClient;
import net.schmizz.sshj.userauth.keyprovider.KeyProvider;
import net.schmizz.sshj.userauth.password.PasswordUtils;

/** The production {@link Sftp.Opener}: sshj, with the server's host key checked against the configured fingerprint before anything is sent. */
final class SshjOpener implements Sftp.Opener {

    @Override
    public Sftp.Session open(Sftp.Target t) throws Exception {
        SSHClient ssh = new SSHClient();
        try {
            ssh.addHostKeyVerifier(t.hostKey()); // mandatory fingerprint; no known_hosts, no trust on first use
            ssh.setConnectTimeout(10_000);
            ssh.setTimeout(30_000);
            ssh.connect(t.host(), t.port());
            if (t.password() != null) {
                ssh.authPassword(t.user(), t.password());
            } else {
                KeyProvider keys = t.passphrase() == null ? ssh.loadKeys(t.privateKey(), null, null)
                        : ssh.loadKeys(t.privateKey(), null, PasswordUtils.createOneOff(t.passphrase().toCharArray()));
                ssh.authPublickey(t.user(), keys);
            }
            return new SshjSession(ssh, ssh.newSFTPClient());
        } catch (Exception e) {
            try {
                ssh.close();
            } catch (Exception ignored) {
                // the original failure is the one that matters
            }
            throw e;
        }
    }

    private static final class SshjSession implements Sftp.Session {
        private final SSHClient ssh;
        private final SFTPClient sftp;

        SshjSession(SSHClient ssh, SFTPClient sftp) {
            this.ssh = ssh;
            this.sftp = sftp;
        }

        @Override
        public List<Sftp.Entry> list(String dir) throws Exception {
            List<Sftp.Entry> out = new ArrayList<>();
            for (RemoteResourceInfo i : sftp.ls(dir)) {
                if (out.size() >= Sftp.MAX_LIST) {
                    break;
                }
                out.add(new Sftp.Entry(i.getName(), i.isRegularFile(), i.getAttributes().getSize(), i.getAttributes().getMtime()));
            }
            return out;
        }

        @Override
        public byte[] read(String path, int max) throws Exception {
            try (RemoteFile f = sftp.open(path); InputStream in = f.new RemoteFileInputStream()) {
                ByteArrayOutputStream out = new ByteArrayOutputStream();
                byte[] buf = new byte[8192];
                int n;
                while ((n = in.read(buf)) > 0) {
                    if (out.size() + n > max) {
                        return null;
                    }
                    out.write(buf, 0, n);
                }
                return out.toByteArray();
            }
        }

        @Override
        public void write(String path, byte[] data, boolean overwrite) throws Exception {
            if (!overwrite && sftp.statExistence(path) != null) {
                throw new java.nio.file.FileAlreadyExistsException("exists");
            }
            String tmp = path + ".wayang-" + UUID.randomUUID().toString().substring(0, 8) + ".tmp";
            try (RemoteFile f = sftp.open(tmp, EnumSet.of(OpenMode.WRITE, OpenMode.CREAT, OpenMode.EXCL)); OutputStream out = f.new RemoteFileOutputStream()) {
                out.write(data);
            }
            try {
                if (overwrite && sftp.statExistence(path) != null) {
                    sftp.rm(path);
                }
                sftp.rename(tmp, path);
            } catch (Exception e) {
                try {
                    sftp.rm(tmp);
                } catch (Exception ignored) {
                    // best effort: do not leave a half-published temp file when the rename failed
                }
                throw e;
            }
        }

        @Override
        public void delete(String path) throws Exception {
            sftp.rm(path);
        }

        @Override
        public void close() {
            try {
                sftp.close();
            } catch (Exception ignored) {
                // closing
            }
            try {
                ssh.close();
            } catch (Exception ignored) {
                // closing
            }
        }
    }
}
