package tech.kayys.wayang.connectors;

import java.io.File;
import java.io.FileInputStream;
import java.nio.file.Files;
import java.security.KeyStore;

import javax.net.ssl.KeyManagerFactory;
import javax.net.ssl.SSLContext;
import javax.net.ssl.TrustManagerFactory;

/** A throw-away self-signed certificate for 127.0.0.1, made with the JDK's keytool, to test real TLS handshakes. */
final class TestTls {
    final SSLContext server;
    final SSLContext client;

    TestTls() throws Exception {
        File dir = Files.createTempDirectory("tls").toFile();
        File ks = new File(dir, "k.p12");
        Process p = new ProcessBuilder("keytool", "-genkeypair", "-alias", "t", "-keyalg", "RSA", "-keysize", "2048", "-dname",
                "CN=cert.invalid", "-ext", "san=ip:127.0.0.1", "-validity", "2", "-keystore", ks.getPath(), "-storepass", "changeit",
                "-storetype", "PKCS12").redirectErrorStream(true).start();
        p.getInputStream().readAllBytes();
        if (p.waitFor() != 0) {
            throw new IllegalStateException("keytool failed");
        }
        KeyStore store = KeyStore.getInstance("PKCS12");
        try (FileInputStream in = new FileInputStream(ks)) {
            store.load(in, "changeit".toCharArray());
        }
        KeyManagerFactory kmf = KeyManagerFactory.getInstance(KeyManagerFactory.getDefaultAlgorithm());
        kmf.init(store, "changeit".toCharArray());
        TrustManagerFactory tmf = TrustManagerFactory.getInstance(TrustManagerFactory.getDefaultAlgorithm());
        tmf.init(store);
        server = SSLContext.getInstance("TLS");
        server.init(kmf.getKeyManagers(), null, null);
        client = SSLContext.getInstance("TLS");
        client.init(null, tmf.getTrustManagers(), null);
        ks.delete();
        dir.delete();
    }
}
