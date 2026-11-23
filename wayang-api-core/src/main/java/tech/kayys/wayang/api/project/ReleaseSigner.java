package tech.kayys.wayang.api.project;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.KeyFactory;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.security.spec.PKCS8EncodedKeySpec;
import java.security.spec.X509EncodedKeySpec;
import java.util.Base64;

/**
 * Public-key (Ed25519) signature of a release archive, verifiable by anyone who has the public key (a registry, the Console, an
 * operator) without holding any secret. Signs {@code wayang-release-v1:<sha256 of the archive>}, the same statement the HMAC covers,
 * so a signature is bound to exact bytes and never doubles as another signature. Keys are the JDK's standard encodings: PKCS#8
 * private, X.509 public, as PEM or bare base64. The private key never leaves this class (no accessor, no {@code toString}).
 */
public final class ReleaseSigner {

    private final PrivateKey key;
    private final String keyId;

    /**
     * @param privatePkcs8 the private key; @param publicX509 its public key (used for the key id and to prove at start-up that the two match)
     * @throws IllegalArgumentException (message without key material) when a key is unreadable or the pair does not match
     */
    public ReleaseSigner(String privatePkcs8, String publicX509) {
        try {
            KeyFactory kf = KeyFactory.getInstance("Ed25519");
            this.key = kf.generatePrivate(new PKCS8EncodedKeySpec(decode(privatePkcs8)));
            PublicKey pub = publicKey(publicX509);
            this.keyId = keyId(pub);
            byte[] probe = "wayang-release-signer-selftest".getBytes(StandardCharsets.UTF_8);
            if (!verify(probe, sign(probe), pub)) {
                throw new IllegalArgumentException("The release signing private key does not match the public key");
            }
        } catch (GeneralSecurityException | RuntimeException e) {
            if (e instanceof IllegalArgumentException iae && iae.getMessage() != null && iae.getMessage().startsWith("The release signing")) {
                throw iae;
            }
            throw new IllegalArgumentException("The release signing keys are not valid Ed25519 keys (PKCS#8 private, X.509 public)");
        }
    }

    /** First 16 hex characters of the SHA-256 of the public key: what a verifier uses to pick the key. */
    public String keyId() {
        return keyId;
    }

    /** @return base64 signature of the archive */
    public String sign(byte[] zip) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initSign(key);
            s.update(statement(zip));
            return Base64.getEncoder().encodeToString(s.sign());
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Signing failed");
        }
    }

    public static boolean verify(byte[] zip, String signatureBase64, PublicKey publicKey) {
        try {
            Signature s = Signature.getInstance("Ed25519");
            s.initVerify(publicKey);
            s.update(statement(zip));
            return s.verify(Base64.getDecoder().decode(signatureBase64));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            return false;
        }
    }

    public static PublicKey publicKey(String x509) throws GeneralSecurityException {
        return KeyFactory.getInstance("Ed25519").generatePublic(new X509EncodedKeySpec(decode(x509)));
    }

    public static String keyId(PublicKey pub) throws GeneralSecurityException {
        return WorkspaceBundle.sha256Hex(pub.getEncoded()).substring(0, 16);
    }

    private static byte[] statement(byte[] zip) {
        return ("wayang-release-v1:" + WorkspaceBundle.sha256Hex(zip)).getBytes(StandardCharsets.UTF_8);
    }

    private static byte[] decode(String pemOrBase64) {
        String b = pemOrBase64.replaceAll("-----(BEGIN|END)[A-Z ]+-----", "").replaceAll("\\s+", "");
        return Base64.getDecoder().decode(b);
    }

    @Override
    public String toString() {
        return "ReleaseSigner[" + keyId + "]";
    }
}
