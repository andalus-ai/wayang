package tech.kayys.wayang.publish;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;
import java.util.HexFormat;

/**
 * Checks a downloaded release archive the way an operator or a deployment handler should before using it: the SHA-256 the design server
 * reported ({@code X-Wayang-Release-Sha256}) and the Ed25519 signature ({@code X-Wayang-Release-Signature-Ed25519}) over
 * {@code wayang-release-v1:<sha256>}, against a public key the operator provisioned independently. A key id the server supplies is never
 * trusted by itself. Reads the file as a stream; it is not extracted or executed.
 *
 * <p>Command line: {@code ReleaseSignatureVerifier <release.zip> <public-key.pem> <signature-base64> [sha256-hex]}; exit 0 verified, 1 not, 2 usage.
 */
public final class ReleaseSignatureVerifier {

    private ReleaseSignatureVerifier() {
    }

    /** @return the archive's SHA-256 (hex) when it matches {@code expectedSha256} (if given) and the signature verifies under the key
     * @throws IOException unreadable or irregular file
     * @throws IllegalArgumentException digest or signature does not match, or the key is not a valid Ed25519 X.509 key (no key material in the message) */
    public static String verify(Path zip, PublicKey trusted, String signatureBase64, String expectedSha256) throws IOException {
        if (Files.isSymbolicLink(zip) || !Files.isRegularFile(zip, LinkOption.NOFOLLOW_LINKS)) {
            throw new IOException("The release archive must be a regular, non-symlink file");
        }
        String sha;
        try (InputStream in = Files.newInputStream(zip)) {
            MessageDigest md = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[1 << 16];
            for (int n; (n = in.read(buf)) > 0;) {
                md.update(buf, 0, n);
            }
            sha = HexFormat.of().formatHex(md.digest());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException(e);
        }
        if (expectedSha256 != null && !MessageDigest.isEqual(sha.getBytes(StandardCharsets.US_ASCII),
                expectedSha256.trim().toLowerCase(java.util.Locale.ROOT).getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("The archive's SHA-256 is not the expected one");
        }
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(trusted);
            s.update(("wayang-release-v1:" + sha).getBytes(StandardCharsets.UTF_8));
            if (!s.verify(Base64.getDecoder().decode(signatureBase64.trim()))) {
                throw new IllegalArgumentException("The release signature does not verify under the trusted key");
            }
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            if (e instanceof IllegalArgumentException iae && iae.getMessage() != null && iae.getMessage().startsWith("The release")) {
                throw iae;
            }
            throw new IllegalArgumentException("The release signature does not verify under the trusted key");
        }
        return sha;
    }

    /** Reads an X.509 Ed25519 public key as PEM or bare base64. */
    public static PublicKey readPublicKey(Path file) throws IOException {
        try {
            String b = Files.readString(file).replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s+", "");
            return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(Base64.getDecoder().decode(b)));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("The public key is not a valid Ed25519 X.509 key");
        }
    }

    public static void main(String[] a) {
        if (a.length < 3 || a.length > 4) {
            System.err.println("usage: ReleaseSignatureVerifier <release.zip> <public-key.pem> <signature-base64> [sha256-hex]");
            System.exit(2);
        }
        try {
            String sha = verify(Path.of(a[0]), readPublicKey(Path.of(a[1])), a[2], a.length == 4 ? a[3] : null);
            System.out.println("verified sha256=" + sha);
        } catch (IOException | IllegalArgumentException e) {
            System.err.println("NOT verified: " + e.getMessage());
            System.exit(1);
        }
    }
}
