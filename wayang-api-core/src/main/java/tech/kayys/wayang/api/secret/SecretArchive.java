package tech.kayys.wayang.api.secret;

import tech.kayys.wayang.api.ApiException;
import tech.kayys.wayang.api.project.WorkspaceBundle;

import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.TreeMap;
import javax.crypto.Cipher;
import javax.crypto.SecretKeyFactory;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.PBEKeySpec;
import javax.crypto.spec.SecretKeySpec;

import tech.kayys.wayang.runtime.json.Json;

/**
 * Moves secrets between servers without ever putting them in a workspace bundle: a passphrase-encrypted file. PBKDF2-HMAC-SHA256
 * (600,000 rounds, random salt) derives an AES-256-GCM key; the header is authenticated, so changing the cost or the format
 * fails the same way a wrong passphrase does. The passphrase is the only protection once the file leaves the server: it must be
 * long, and shared out of band.
 */
public final class SecretArchive {

    public static final String FORMAT = "wayang.secrets";
    public static final int ITERATIONS = 600_000;
    public static final int MIN_PASSPHRASE = 12;
    public static final int MAX_SECRETS = 1000;
    private static final SecureRandom RNG = new SecureRandom();

    private SecretArchive() {
    }

    public static void checkPassphrase(String passphrase) {
        if (passphrase == null || passphrase.length() < MIN_PASSPHRASE || passphrase.length() > 256 || passphrase.isBlank()) {
            throw ApiException.badRequest("The passphrase must be " + MIN_PASSPHRASE + " to 256 characters");
        }
        if (passphrase.chars().distinct().count() < 5) {
            throw ApiException.badRequest("The passphrase is too repetitive");
        }
    }

    public static Map<String, Object> seal(String passphrase, Map<String, String> secrets) {
        return seal(passphrase, secrets, ITERATIONS);
    }

    static Map<String, Object> seal(String passphrase, Map<String, String> secrets, int iterations) {
        checkPassphrase(passphrase);
        if (secrets.size() > MAX_SECRETS) {
            throw ApiException.badRequest("At most " + MAX_SECRETS + " secrets per archive");
        }
        byte[] salt = new byte[16];
        byte[] iv = new byte[12];
        RNG.nextBytes(salt);
        RNG.nextBytes(iv);
        Map<String, Object> header = new LinkedHashMap<>();
        header.put("format", FORMAT);
        header.put("formatVersion", 1);
        header.put("kdf", "PBKDF2-HMAC-SHA256");
        header.put("iterations", iterations);
        header.put("salt", Base64.getEncoder().encodeToString(salt));
        header.put("cipher", "AES-256-GCM");
        header.put("iv", Base64.getEncoder().encodeToString(iv));
        try {
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, key(passphrase, salt, iterations), new GCMParameterSpec(128, iv));
            c.updateAAD(WorkspaceBundle.canonical(header).getBytes(StandardCharsets.UTF_8));
            byte[] ct = c.doFinal(Json.write(new TreeMap<>(secrets)).getBytes(StandardCharsets.UTF_8));
            Map<String, Object> out = new LinkedHashMap<>(header);
            out.put("ciphertext", Base64.getEncoder().encodeToString(ct));
            return out;
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot seal the archive", e);
        }
    }

    /** @throws IllegalArgumentException "Cannot open the archive" for a wrong passphrase, any change, or a bad format */
    @SuppressWarnings("unchecked")
    public static Map<String, String> open(String passphrase, Map<String, Object> archive) {
        try {
            if (passphrase == null || archive == null || !FORMAT.equals(archive.get("format")) || !(archive.get("iterations") instanceof Number it)
                    || it.intValue() < 100_000 || it.intValue() > 5_000_000 || !"PBKDF2-HMAC-SHA256".equals(archive.get("kdf"))
                    || !"AES-256-GCM".equals(archive.get("cipher"))) {
                throw new IllegalArgumentException("Cannot open the archive");
            }
            Map<String, Object> header = new LinkedHashMap<>(archive);
            String ct = (String) header.remove("ciphertext");
            byte[] salt = Base64.getDecoder().decode((String) header.get("salt"));
            byte[] iv = Base64.getDecoder().decode((String) header.get("iv"));
            if (iv.length != 12 || salt.length < 16 || ct == null) {
                throw new IllegalArgumentException("Cannot open the archive");
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, key(passphrase, salt, it.intValue()), new GCMParameterSpec(128, iv));
            c.updateAAD(WorkspaceBundle.canonical(header).getBytes(StandardCharsets.UTF_8));
            Object json = Json.read(new String(c.doFinal(Base64.getDecoder().decode(ct)), StandardCharsets.UTF_8));
            Map<String, String> out = new LinkedHashMap<>();
            for (var e : ((Map<String, Object>) json).entrySet()) {
                if (!(e.getValue() instanceof String s)) {
                    throw new IllegalArgumentException("Cannot open the archive");
                }
                out.put(e.getKey(), s);
            }
            return out;
        } catch (GeneralSecurityException | RuntimeException e) {
            throw new IllegalArgumentException("Cannot open the archive");
        }
    }

    private static SecretKeySpec key(String passphrase, byte[] salt, int iterations) throws GeneralSecurityException {
        PBEKeySpec spec = new PBEKeySpec(passphrase.toCharArray(), salt, iterations, 256);
        try {
            return new SecretKeySpec(SecretKeyFactory.getInstance("PBKDF2WithHmacSHA256").generateSecret(spec).getEncoded(), "AES");
        } finally {
            spec.clearPassword();
        }
    }
}
