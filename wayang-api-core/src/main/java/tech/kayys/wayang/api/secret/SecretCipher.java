package tech.kayys.wayang.api.secret;

import java.nio.ByteBuffer;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.SecureRandom;
import java.util.Base64;
import java.util.Map;
import javax.crypto.Cipher;
import javax.crypto.spec.GCMParameterSpec;
import javax.crypto.spec.SecretKeySpec;

/**
 * AES-256-GCM with a random 96-bit IV per seal and AAD = tenant, name, version, so a ciphertext cannot be moved
 * to another tenant, name or version. Keys come from a keyring (keyId → 32 bytes) to allow rotation: new seals use
 * {@code activeKeyId}, old ones stay readable while their key remains in the ring.
 */
public final class SecretCipher {

    private static final SecureRandom RNG = new SecureRandom();
    private final Map<String, byte[]> keys;
    private final String activeKeyId;

    public SecretCipher(Map<String, byte[]> keys, String activeKeyId) {
        if (keys == null || !keys.containsKey(activeKeyId)) {
            throw new IllegalArgumentException("Active key '" + activeKeyId + "' is not in the keyring");
        }
        Map<String, byte[]> copy = new java.util.HashMap<>();
        keys.forEach((id, k) -> {
            if (!id.matches("[A-Za-z0-9_.-]{1,32}")) {
                throw new IllegalArgumentException("Bad key id");
            }
            if (k == null || k.length != 32) {
                throw new IllegalArgumentException("Key '" + id + "' must be exactly 32 bytes");
            }
            copy.put(id, k.clone());
        });
        this.keys = Map.copyOf(copy);
        this.activeKeyId = activeKeyId;
    }

    public String activeKeyId() {
        return activeKeyId;
    }

    public java.util.Set<String> keyIds() {
        return keys.keySet();
    }

    public boolean knows(String keyId) {
        return keys.containsKey(keyId);
    }

    /** @return the sealed form under the active key */
    public String seal(String tenant, String name, int version, String plaintext) {
        try {
            byte[] iv = new byte[12];
            RNG.nextBytes(iv);
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.ENCRYPT_MODE, new SecretKeySpec(keys.get(activeKeyId), "AES"), new GCMParameterSpec(128, iv));
            c.updateAAD(aad(tenant, name, version));
            byte[] ct = c.doFinal(plaintext.getBytes(StandardCharsets.UTF_8));
            byte[] out = ByteBuffer.allocate(iv.length + ct.length).put(iv).put(ct).array();
            return "v1:" + activeKeyId + ":" + Base64.getEncoder().encodeToString(out);
        } catch (GeneralSecurityException e) {
            throw new IllegalStateException("Cannot seal secret", e);
        }
    }

    public static String keyIdOf(String sealed) {
        String[] p = sealed.split(":", 3);
        if (p.length != 3 || !p[0].equals("v1")) {
            throw new IllegalArgumentException("Unrecognised sealed format");
        }
        return p[1];
    }

    /** @throws IllegalArgumentException on tamper, wrong context, unknown key or bad format (message never contains data) */
    public String open(String tenant, String name, int version, String sealed) {
        try {
            String[] p = sealed.split(":", 3);
            if (p.length != 3 || !p[0].equals("v1") || !keys.containsKey(p[1])) {
                throw new IllegalArgumentException("Cannot open secret");
            }
            byte[] all = Base64.getDecoder().decode(p[2]);
            if (all.length < 12 + 16) {
                throw new IllegalArgumentException("Cannot open secret");
            }
            Cipher c = Cipher.getInstance("AES/GCM/NoPadding");
            c.init(Cipher.DECRYPT_MODE, new SecretKeySpec(keys.get(p[1]), "AES"),
                    new GCMParameterSpec(128, all, 0, 12));
            c.updateAAD(aad(tenant, name, version));
            return new String(c.doFinal(all, 12, all.length - 12), StandardCharsets.UTF_8);
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalArgumentException("Cannot open secret");
        }
    }

    private static byte[] aad(String tenant, String name, int version) {
        return (tenant + "\0" + name + "\0" + version).getBytes(StandardCharsets.UTF_8);
    }
}
