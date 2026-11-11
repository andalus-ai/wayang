package tech.kayys.wayang.publish;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.MessageDigest;
import java.security.PrivateKey;
import java.security.PublicKey;
import java.security.Signature;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Creates and verifies an Ed25519-signed statement that an offline package was verified.
 * The receipt signer is the installation authority, distinct from the release signer.
 */
public final class ReleaseInstallationReceipt {
    public record SignedReceipt(String json, String sha256, String signerKeySha256) {}
    public record VerifiedReceipt(String packageSha256, String lockSha256, String manifestSha256,
            String sbomSha256, int artifactCount, String signerKeySha256) {}

    private ReleaseInstallationReceipt() {}

    public static SignedReceipt create(ReleaseOfflinePackageVerifier.VerifiedPackage verified,
            PrivateKey installationPrivateKey, PublicKey installationPublicKey) {
        Objects.requireNonNull(verified, "verified");
        Objects.requireNonNull(installationPrivateKey, "installationPrivateKey");
        Objects.requireNonNull(installationPublicKey, "installationPublicKey");
        String keyFingerprint = sha256(installationPublicKey.getEncoded());
        Map<String, Object> document = new LinkedHashMap<>();
        document.put("format", "wayang.installation-receipt");
        document.put("formatVersion", 1);
        document.put("packageSha256", verified.packageSha256());
        document.put("lockSha256", verified.lockSha256());
        document.put("manifestSha256", verified.manifestSha256());
        document.put("sbomSha256", verified.sbomSha256());
        document.put("artifactCount", verified.artifactCount());
        document.put("artifactDigests", new TreeMap<>(verified.artifactDigests()));
        document.put("signerKeySha256", keyFingerprint);
        try {
            Signature signer = Signature.getInstance("Ed25519");
            signer.initSign(installationPrivateKey);
            signer.update(Json.write(canonical(document)).getBytes(StandardCharsets.UTF_8));
            document.put("signature", Base64.getEncoder().encodeToString(signer.sign()));
            String json = Json.write(document);
            return new SignedReceipt(json, sha256(json.getBytes(StandardCharsets.UTF_8)), keyFingerprint);
        } catch (GeneralSecurityException e) {
            throw new IllegalArgumentException("Unable to sign installation receipt", e);
        }
    }

    public static VerifiedReceipt verify(String receiptJson, PublicKey trustedInstallationSigner) {
        Objects.requireNonNull(trustedInstallationSigner, "trustedInstallationSigner");
        if (receiptJson == null || receiptJson.isBlank()) throw new IllegalArgumentException("Installation receipt is empty");
        Object parsed = Json.read(receiptJson);
        if (!(parsed instanceof Map<?, ?> raw)) throw new IllegalArgumentException("Installation receipt must be a JSON object");
        Map<String, Object> document = stringMap(raw);
        if (!"wayang.installation-receipt".equals(document.get("format"))
                || !(document.get("formatVersion") instanceof Number version)
                || version.intValue() != 1 || version.doubleValue() != 1.0d) {
            throw new IllegalArgumentException("Unsupported installation receipt format");
        }
        String fingerprint = requiredString(document.get("signerKeySha256"), "signerKeySha256");
        if (!MessageDigest.isEqual(fingerprint.getBytes(StandardCharsets.US_ASCII),
                sha256(trustedInstallationSigner.getEncoded()).getBytes(StandardCharsets.US_ASCII))) {
            throw new IllegalArgumentException("Installation receipt signer does not match trusted key");
        }
        String encoded = requiredString(document.get("signature"), "signature");
        byte[] signatureBytes;
        try {
            signatureBytes = Base64.getDecoder().decode(encoded);
            Map<String, Object> unsigned = new LinkedHashMap<>(document);
            unsigned.remove("signature");
            Signature verifier = Signature.getInstance("Ed25519");
            verifier.initVerify(trustedInstallationSigner);
            verifier.update(Json.write(canonical(unsigned)).getBytes(StandardCharsets.UTF_8));
            if (!verifier.verify(signatureBytes)) throw new IllegalArgumentException("Installation receipt signature verification failed");
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            if (e instanceof IllegalArgumentException iae) throw iae;
            throw new IllegalArgumentException("Unable to verify installation receipt signature", e);
        }
        return new VerifiedReceipt(requiredString(document.get("packageSha256"), "packageSha256"),
                requiredString(document.get("lockSha256"), "lockSha256"),
                requiredString(document.get("manifestSha256"), "manifestSha256"),
                requiredString(document.get("sbomSha256"), "sbomSha256"),
                requiredInt(document.get("artifactCount"), "artifactCount"), fingerprint);
    }

    private static int requiredInt(Object value, String field) {
        if (!(value instanceof Number number) || number.intValue() < 0 || number.doubleValue() != number.intValue()) {
            throw new IllegalArgumentException("Missing or invalid " + field);
        }
        return number.intValue();
    }

    private static String requiredString(Object value, String field) {
        if (!(value instanceof String text) || text.isBlank()) throw new IllegalArgumentException("Missing or invalid " + field);
        return text;
    }

    private static Map<String, Object> stringMap(Map<?, ?> map) {
        Map<String, Object> result = new LinkedHashMap<>();
        map.forEach((key, value) -> {
            if (!(key instanceof String name)) throw new IllegalArgumentException("Receipt object keys must be strings");
            result.put(name, value);
        });
        return result;
    }

    private static Object canonical(Object value) {
        if (value instanceof Map<?, ?> map) {
            Map<String, Object> sorted = new TreeMap<>();
            map.forEach((key, nested) -> sorted.put(String.valueOf(key), canonical(nested)));
            return sorted;
        }
        if (value instanceof java.util.List<?> list) return list.stream().map(ReleaseInstallationReceipt::canonical).toList();
        return value;
    }

    private static String sha256(byte[] bytes) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(bytes));
        } catch (java.security.NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is unavailable", e);
        }
    }
}
