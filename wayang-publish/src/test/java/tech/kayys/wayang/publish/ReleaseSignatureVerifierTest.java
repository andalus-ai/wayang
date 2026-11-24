package tech.kayys.wayang.publish;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertThrows;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.MessageDigest;
import java.security.Signature;
import java.util.Base64;
import java.util.HexFormat;

import org.junit.jupiter.api.Test;

class ReleaseSignatureVerifierTest {

    final Path dir = tmp();

    private static Path tmp() {
        try {
            Path d = Files.createTempDirectory("wy-verify");
            d.toFile().deleteOnExit();
            return d;
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    static String sign(KeyPair kp, byte[] zip) throws Exception {
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));
        Signature s = Signature.getInstance("Ed25519");
        s.initSign(kp.getPrivate());
        s.update(("wayang-release-v1:" + sha).getBytes(StandardCharsets.UTF_8));
        return Base64.getEncoder().encodeToString(s.sign());
    }

    @Test
    void verifiesTheExactBytesUnderTheTrustedKeyOnly() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        KeyPair other = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        byte[] zip = "release-bytes".getBytes(StandardCharsets.UTF_8);
        Path f = Files.write(dir.resolve("r.zip"), zip);
        String sig = sign(kp, zip);
        String sha = HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(zip));

        assertEquals(sha, ReleaseSignatureVerifier.verify(f, kp.getPublic(), sig, null));
        assertEquals(sha, ReleaseSignatureVerifier.verify(f, kp.getPublic(), sig, sha.toUpperCase()));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSignatureVerifier.verify(f, other.getPublic(), sig, null));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSignatureVerifier.verify(f, kp.getPublic(), sig, "0".repeat(64)));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSignatureVerifier.verify(f, kp.getPublic(), "not base64!", null));
        Path tampered = Files.write(dir.resolve("t.zip"), "release-bytez".getBytes(StandardCharsets.UTF_8));
        assertThrows(IllegalArgumentException.class, () -> ReleaseSignatureVerifier.verify(tampered, kp.getPublic(), sig, null));
    }

    @Test
    void readsPemKeysAndRefusesBadKeysAndIrregularFiles() throws Exception {
        KeyPair kp = KeyPairGenerator.getInstance("Ed25519").generateKeyPair();
        String pem = "-----BEGIN PUBLIC KEY-----\n" + Base64.getMimeEncoder(64, "\n".getBytes()).encodeToString(kp.getPublic().getEncoded())
                + "\n-----END PUBLIC KEY-----\n";
        assertEquals(kp.getPublic(), ReleaseSignatureVerifier.readPublicKey(Files.writeString(dir.resolve("k.pem"), pem)));
        var bad = assertThrows(IllegalArgumentException.class, () -> ReleaseSignatureVerifier.readPublicKey(Files.writeString(dir.resolve("b.pem"), "junk")));
        assertFalse(bad.getMessage().contains("junk"));
        assertThrows(IOException.class, () -> ReleaseSignatureVerifier.verify(dir.resolve("missing.zip"), kp.getPublic(), "x", null));
        Path real = Files.write(dir.resolve("a.zip"), new byte[] {1});
        Path link = dir.resolve("l.zip");
        try {
            Files.createSymbolicLink(link, real);
            assertThrows(IOException.class, () -> ReleaseSignatureVerifier.verify(link, kp.getPublic(), "x", null));
        } catch (UnsupportedOperationException | IOException skip) {
            // no symlinks on this file system
        }
    }
}
